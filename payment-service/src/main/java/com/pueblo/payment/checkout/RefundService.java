package com.pueblo.payment.checkout;

import com.fasterxml.jackson.databind.*;
import com.pueblo.messaging.*;
import com.pueblo.payment.gateway.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Full compensation refunds only. Authenticated commands are persisted before external calls. */
public final class RefundService {
    public record Request(UUID refundId,UUID paymentId,UUID orderId,long amount,String currency) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TossClient toss;
    private final JdbcOutbox outbox;
    private final ObjectMapper mapper;
    public RefundService(JdbcTemplate jdbc,PlatformTransactionManager manager,TossClient toss,JdbcOutbox outbox,ObjectMapper mapper) {
        this.jdbc=jdbc;this.toss=toss;this.outbox=outbox;this.mapper=mapper;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public void accept(Request request) {
        tx.executeWithoutResult(t->{
            var payment=jdbc.queryForMap("SELECT id,order_id,amount,status FROM payment_attempt WHERE id=? FOR UPDATE",request.paymentId().toString());
            if(!payment.get("order_id").equals(request.orderId().toString())||((Number)payment.get("amount")).longValue()!=request.amount()
                    ||!"KRW".equals(request.currency())||!"SUCCEEDED".equals(payment.get("status"))) throw new IllegalArgumentException("Refund payment mismatch");
            var existing=jdbc.queryForList("SELECT id FROM refund_attempt WHERE payment_id=?",String.class,request.paymentId().toString());
            if(!existing.isEmpty()) {
                if(!existing.getFirst().equals(request.refundId().toString())) throw new IllegalArgumentException("Refund identity conflict");
                return;
            }
            jdbc.update("INSERT INTO refund_attempt(id,payment_id,order_id,amount) VALUES(?,?,?,?)",request.refundId().toString(),request.paymentId().toString(),request.orderId().toString(),request.amount());
        });
    }
    public int recoverBatch() {
        var ids=jdbc.queryForList("""
                SELECT id FROM refund_attempt WHERE status IN ('PENDING','PROCESSING','UNKNOWN')
                AND next_check_at<=UTC_TIMESTAMP(6) AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6)) LIMIT 10
                """,String.class);
        for(String id:ids) process(id);
        return ids.size();
    }
    private record Attempt(String id,String payment,String order,long amount,String state,int checks,String key) {
        @Override public String toString() {return "RefundAttempt[redacted]";}
    }
    private void process(String id) {
        String owner=UUID.randomUUID().toString();
        Attempt row=tx.execute(t->{
            var rows=jdbc.query("""
                    SELECT r.*,p.payment_key FROM refund_attempt r JOIN payment_attempt p ON p.id=r.payment_id
                    WHERE r.id=? FOR UPDATE
                    """,(r,n)->new Attempt(r.getString("id"),r.getString("payment_id"),r.getString("order_id"),r.getLong("amount"),r.getString("status"),r.getInt("checks"),r.getString("payment_key")),id);
            if(rows.isEmpty()) return null;
            int changed=jdbc.update("""
                    UPDATE refund_attempt SET lease_owner=?,lease_until=UTC_TIMESTAMP(6)+INTERVAL 60 SECOND,status='PROCESSING',checks=checks+1
                    WHERE id=? AND status IN ('PENDING','PROCESSING','UNKNOWN') AND next_check_at<=UTC_TIMESTAMP(6)
                    AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))
                    """,owner,id);
            return changed==1?rows.getFirst():null;
        });
        if(row==null) return;
        String outcome="UNKNOWN";
        try {
            // Only first persisted PENDING work sends cancel. Uncertain/crashed work is queried, never blindly replayed.
            var response=row.state.equals("PENDING")?toss.refund(row.key,UUID.fromString(id)):toss.find(row.key);
            outcome=outcome(response,row);
        } catch(JsonHttp.Unavailable ex) { /* No inference from transport failure. */ }
        if(outcome.equals("UNKNOWN")&&row.checks+1>=20) outcome="REVIEW_REQUIRED";
        String result=outcome;
        tx.executeWithoutResult(t->{
            var locked=jdbc.queryForMap("SELECT lease_owner,version FROM refund_attempt WHERE id=? FOR UPDATE",id);
            if(!owner.equals(locked.get("lease_owner"))) return;
            long version=((Number)locked.get("version")).longValue();
            boolean publish=Set.of("SUCCEEDED","REVIEW_REQUIRED").contains(result);
            if(publish) version++;
            jdbc.update("UPDATE refund_attempt SET status=?,version=?,lease_owner=NULL,lease_until=NULL,next_check_at=UTC_TIMESTAMP(6)+INTERVAL 10 SECOND WHERE id=?",result,version,id);
            if(publish) {
                var payload=mapper.valueToTree(Map.of("refundId",id,"paymentId",row.payment,"orderId",row.order,"amount",row.amount,"currency","KRW","status",result,"version",version));
                outbox.append(new OutboxRecord(PaymentService.RESULTS_TOPIC,row.order,new EventEnvelope(UUID.randomUUID(),result.equals("SUCCEEDED")?"RefundSucceeded":"RefundUncertain",1,"payment-service",row.order,version,Instant.now(),row.order,id,null,payload)));
            }
        });
    }
    private String outcome(JsonHttp.Reply reply,Attempt row) {
        JsonNode p=reply.body();
        if(reply.status()!=200||p==null||!p.isObject()) return "UNKNOWN";
        if(!row.order.equals(p.path("orderId").asText())||!row.key.equals(p.path("paymentKey").asText())||!"KRW".equals(p.path("currency").asText())
                ||!p.path("totalAmount").isIntegralNumber()||!p.path("totalAmount").canConvertToLong()||p.path("totalAmount").longValue()!=row.amount) return "REVIEW_REQUIRED";
        if("CANCELED".equals(p.path("status").asText()) && p.path("balanceAmount").isIntegralNumber()&&p.path("balanceAmount").canConvertToLong()&&p.path("balanceAmount").longValue()==0) {
            long sum=0;
            if(!p.path("cancels").isArray()) return "REVIEW_REQUIRED";
            try {
                for(JsonNode cancel:p.path("cancels")) {
                    if(!"DONE".equals(cancel.path("cancelStatus").asText())||!cancel.path("cancelAmount").isIntegralNumber()||!cancel.path("cancelAmount").canConvertToLong()||cancel.path("cancelAmount").longValue()<=0) return "REVIEW_REQUIRED";
                    sum=Math.addExact(sum,cancel.path("cancelAmount").longValue());
                }
            } catch(ArithmeticException ex) {return "REVIEW_REQUIRED";}
            return sum==row.amount?"SUCCEEDED":"REVIEW_REQUIRED";
        }
        return "DONE".equals(p.path("status").asText())?"UNKNOWN":"REVIEW_REQUIRED";
    }
}
