package com.pueblo.order.payment;

import com.pueblo.common.exception.BusinessException;
import com.pueblo.common.exception.CommonErrorCode;
import com.pueblo.order.exception.OrderErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.pueblo.order.payment.PaymentPreparationModels.*;

/** Owns only the order-side authorization record. Payment provider calls belong to payment-service. */
public final class PaymentPreparationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public PaymentPreparationService(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.jdbc=jdbc; tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(10);
    }
    public Snapshot begin(UUID user,UUID orderId,Request request) {
        try {
            return tx.execute(status->{
                var order=jdbc.query("SELECT user_id,status,total_amount,reservation_id,expires_at,release_requested FROM customer_order WHERE id=? FOR UPDATE",
                        (r,n)->new Order(UUID.fromString(r.getString(1)),r.getString(2),r.getLong(3),r.getString(4),
                                r.getTimestamp(5)==null?null:r.getTimestamp(5).toInstant(),r.getBoolean(6)),orderId.toString())
                        .stream().findFirst().orElseThrow(()->new BusinessException(CommonErrorCode.NOT_FOUND));
                if(!order.user.equals(user)) throw new BusinessException(CommonErrorCode.NOT_FOUND);
                if(request.amount()!=order.amount || !"KRW".equals(request.currency()))
                    throw new BusinessException(OrderErrorCode.PAYMENT_AMOUNT_MISMATCH);
                var existing=jdbc.queryForList("SELECT payment_id FROM order_payment_attempt WHERE order_id=?",String.class,orderId.toString());
                if(!existing.isEmpty()) {
                    if(!existing.getFirst().equals(request.paymentId().toString())) throw new BusinessException(OrderErrorCode.PAYMENT_ALREADY_STARTED);
                    // A replay never creates a new attempt or extends the original deadline.
                    return snapshot(orderId);
                }
                if(!order.state.equals("PENDING_PAYMENT") || order.releasing) throw new BusinessException(OrderErrorCode.PAYMENT_NOT_READY);
                if(order.expires==null || !order.expires.isAfter(now())) throw new BusinessException(OrderErrorCode.PAYMENT_WINDOW_EXPIRED);
                jdbc.update("INSERT INTO order_payment_attempt(order_id,payment_id,amount,currency,reservation_id,expires_at) VALUES(?,?,?,'KRW',?,?)",
                        orderId.toString(),request.paymentId().toString(),order.amount,order.reservation,Timestamp.from(order.expires));
                jdbc.update("UPDATE customer_order SET status='PAYMENT_PROCESSING',reason=NULL WHERE id=?",orderId.toString());
                return snapshot(orderId);
            });
        } catch(DuplicateKeyException ex) { throw new BusinessException(OrderErrorCode.PAYMENT_ALREADY_STARTED); }
    }
    public Snapshot get(UUID orderId) {
        // Service-only recovery read: no dependency on the now possibly expired browser access token.
        return snapshot(orderId);
    }
    private Snapshot snapshot(UUID id) {
        return jdbc.query("""
                SELECT p.*,o.status,o.release_requested,o.expires_at AS order_expires_at,UTC_TIMESTAMP(6) AS db_now
                FROM order_payment_attempt p JOIN customer_order o ON o.id=p.order_id WHERE p.order_id=?
                """,(r,n)->new Snapshot(id,UUID.fromString(r.getString("payment_id")),r.getLong("amount"),r.getString("currency"),
                UUID.fromString(r.getString("reservation_id")),r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant(),r.getString("status"),
                "PAYMENT_PROCESSING".equals(r.getString("status")) && !r.getBoolean("release_requested")
                        && r.getTimestamp("order_expires_at")!=null && r.getTimestamp("order_expires_at").after(r.getTimestamp("db_now"))
                        && r.getTimestamp("expires_at").after(r.getTimestamp("db_now"))?"CONTINUE_SAME_ATTEMPT":"RECONCILE_ONLY"),id.toString())
                .stream().findFirst().orElseThrow(()->new BusinessException(CommonErrorCode.NOT_FOUND));
    }
    private Instant now() { return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)",Timestamp.class).toInstant(); }
    private record Order(UUID user,String state,long amount,String reservation,Instant expires,boolean releasing) { }
}
