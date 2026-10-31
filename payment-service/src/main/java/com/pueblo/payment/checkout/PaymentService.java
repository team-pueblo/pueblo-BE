package com.pueblo.payment.checkout;

import com.fasterxml.jackson.databind.*;
import com.pueblo.common.exception.*;
import com.pueblo.messaging.*;
import com.pueblo.payment.gateway.*;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.pueblo.payment.checkout.PaymentModels.*;

public final class PaymentService {
    public static final String RESULTS_TOPIC="pueblo.payment.results.v1";
    private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final ServiceClients clients;
    private final TossClient toss; private final JdbcOutbox outbox; private final ObjectMapper mapper;
    public PaymentService(JdbcTemplate jdbc,PlatformTransactionManager manager,ServiceClients clients,TossClient toss,JdbcOutbox outbox,ObjectMapper mapper) {
        this.jdbc=jdbc;this.clients=clients;this.toss=toss;this.outbox=outbox;this.mapper=mapper;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public View confirm(UUID user,String bearer,Confirm request) {
        // Verify authoritative ownership before a new row can occupy an order's unique slot.
        JsonNode order=clients.order(request.orderId(),bearer);
        if(!order.path("totalAmount").isIntegralNumber()||order.path("totalAmount").longValue()!=request.amount()||!"KRW".equals(order.path("currency").asText()))
            throw new BusinessException(PaymentErrorCode.PAYMENT_TOTAL_MISMATCH);
        try {
            tx.executeWithoutResult(s->jdbc.update("INSERT INTO payment_attempt(id,order_id,user_id,payment_key,amount,next_check_at) VALUES(?,?,?,?,?,UTC_TIMESTAMP(6)+INTERVAL 60 SECOND)",
                    UUID.randomUUID().toString(),request.orderId().toString(),user.toString(),request.paymentKey(),request.amount()));
        } catch(DuplicateKeyException e) { /* Validate stored identity below; never silently substitute a different request. */ }
        Attempt row=owned(user,request.orderId());
        if(!row.key.equals(request.paymentKey())||row.amount!=request.amount()) throw new BusinessException(PaymentErrorCode.PAYMENT_REQUEST_CONFLICT);
        String owner=UUID.randomUUID().toString();
        boolean claimed=Boolean.TRUE.equals(tx.execute(s->jdbc.update("""
                UPDATE payment_attempt SET lease_owner=?,lease_until=UTC_TIMESTAMP(6)+INTERVAL 60 SECOND
                WHERE id=? AND status='PREPARING' AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))
                """,owner,row.id.toString())==1));
        if(!claimed) return view(row(request.orderId()));
        try {
            JsonNode ready=clients.begin(row.order,row.id,row.amount,bearer);
            if(!matches(ready,row)) { finish(row,owner,"REVIEW_REQUIRED"); return view(row(row.order)); }
            boolean allowed="CONTINUE_SAME_ATTEMPT".equals(ready.path("nextAction").asText())
                    && Instant.parse(ready.path("expiresAt").asText()).isAfter(Instant.now());
            if(!allowed) { finish(row,owner,"UNKNOWN"); return view(row(row.order)); }
            // Commit intent before any PG call. No HTTP call runs under a DB transaction.
            boolean processing=Boolean.TRUE.equals(tx.execute(s->jdbc.update("UPDATE payment_attempt SET status='PROCESSING' WHERE id=? AND lease_owner=? AND status='PREPARING'",row.id.toString(),owner)==1));
            if(!processing) return view(row(row.order));
            String outcome;
            try { outcome=TossClient.outcome(toss.confirm(row.order,row.key,row.amount,row.id),row.order,row.key,row.amount); }
            catch(JsonHttp.Unavailable e) { outcome="UNKNOWN"; }
            finish(row,owner,outcome);
        } catch(BusinessException e) {
            // The order may have accepted a timed-out request. Recovery must inspect it, not infer failure.
            releaseLease(row,owner); throw e;
        } catch(IllegalArgumentException e) { finish(row,owner,"REVIEW_REQUIRED"); }
        return view(row(row.order));
    }
    public View get(UUID user,UUID order) { return view(owned(user,order)); }
    public int reconcileBatch() {
        var ids=jdbc.queryForList("""
                SELECT order_id FROM payment_attempt WHERE status IN ('PREPARING','PROCESSING','UNKNOWN')
                AND next_check_at<=UTC_TIMESTAMP(6) AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))
                ORDER BY next_check_at,id LIMIT 10
                """,String.class);
        for(String id:ids) reconcile(UUID.fromString(id));
        return ids.size();
    }
    private void reconcile(UUID orderId) {
        String owner=UUID.randomUUID().toString();
        boolean claimed=Boolean.TRUE.equals(tx.execute(s->jdbc.update("""
                UPDATE payment_attempt SET lease_owner=?,lease_until=UTC_TIMESTAMP(6)+INTERVAL 60 SECOND,checks=checks+1
                WHERE order_id=? AND status IN ('PREPARING','PROCESSING','UNKNOWN')
                AND next_check_at<=UTC_TIMESTAMP(6) AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))
                """,owner,orderId.toString())==1));
        if(!claimed) return;
        Attempt row=row(orderId);
        String outcome="UNKNOWN";
        try {
            // Recovery never needs to retain a browser JWT and never sends another confirm.
            JsonNode ready=clients.attempt(orderId);
            outcome=matches(ready,row)?TossClient.outcome(toss.find(orderId),row.order,row.key,row.amount):"REVIEW_REQUIRED";
        } catch(BusinessException|JsonHttp.Unavailable e) { /* Missing/unknown provider or order data is not proof of failure. */ }
        if(outcome.equals("UNKNOWN")&&row.checks>=20) outcome="REVIEW_REQUIRED";
        finish(row,owner,outcome);
    }
    private boolean matches(JsonNode data,Attempt row) {
        return row.id.toString().equals(data.path("paymentId").asText())&&row.order.toString().equals(data.path("orderId").asText())
                && data.path("amount").isIntegralNumber()&&data.path("amount").longValue()==row.amount&&"KRW".equals(data.path("currency").asText());
    }
    private void releaseLease(Attempt row,String owner) {
        tx.executeWithoutResult(s->jdbc.update("UPDATE payment_attempt SET lease_owner=NULL,lease_until=NULL,next_check_at=UTC_TIMESTAMP(6)+INTERVAL 10 SECOND WHERE id=? AND lease_owner=?",row.id.toString(),owner));
    }
    private void finish(Attempt original,String owner,String outcome) {
        tx.executeWithoutResult(s->{
            Attempt current=jdbc.query("SELECT * FROM payment_attempt WHERE id=? FOR UPDATE",(r,n)->read(r),original.id.toString()).getFirst();
            if(!owner.equals(current.lease)) return;
            long version=current.version+(current.state.equals(outcome)?0:1);
            jdbc.update("UPDATE payment_attempt SET status=?,version=?,lease_owner=NULL,lease_until=NULL,next_check_at=UTC_TIMESTAMP(6)+INTERVAL 10 SECOND WHERE id=?",outcome,version,current.id.toString());
            if(version==current.version) return;
            String type=switch(outcome) { case "SUCCEEDED"->"PaymentSucceeded"; case "FAILED"->"PaymentFailed"; default->"PaymentUncertain"; };
            var payload=mapper.valueToTree(Map.of("paymentId",current.id,"orderId",current.order,"amount",current.amount,"currency","KRW","status",outcome,"version",version));
            var event=new EventEnvelope(UUID.randomUUID(),type,1,"payment-service",current.order.toString(),version,Instant.now(),current.order.toString(),current.id.toString(),null,payload);
            outbox.append(new OutboxRecord(RESULTS_TOPIC,current.order.toString(),event));
        });
    }
    private Attempt owned(UUID user,UUID id) { Attempt row=row(id);if(!row.user.equals(user)) throw new BusinessException(CommonErrorCode.NOT_FOUND);return row; }
    private Attempt row(UUID id) {
        return jdbc.query("SELECT * FROM payment_attempt WHERE order_id=?",(r,n)->read(r),id.toString()).stream().findFirst().orElseThrow(()->new BusinessException(CommonErrorCode.NOT_FOUND));
    }
    private Attempt read(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Attempt(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("order_id")),UUID.fromString(r.getString("user_id")),r.getString("payment_key"),r.getLong("amount"),r.getString("status"),r.getString("lease_owner"),r.getLong("version"),r.getInt("checks"));
    }
    private View view(Attempt row) { return new View(row.id,row.order,row.amount,"KRW",row.state); }
    private record Attempt(UUID id,UUID order,UUID user,String key,long amount,String state,String lease,long version,int checks) {
        @Override public String toString() { return "Attempt[redacted]"; }
    }
}
