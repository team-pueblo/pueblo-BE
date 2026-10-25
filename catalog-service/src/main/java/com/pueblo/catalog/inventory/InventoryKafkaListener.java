package com.pueblo.catalog.inventory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pueblo.messaging.EventEnvelope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"pueblo.inventory.enabled", "pueblo.inventory.listener-enabled"}, havingValue = "true")
public final class InventoryKafkaListener {
    public static final String COMMANDS_TOPIC = "pueblo.inventory.commands.v1";
    public static final String SIGNATURE_HEADER = "pueblo-inventory-signature";
    private final InventoryService service;
    private final ObjectMapper mapper;
    private final byte[] secret;

    public InventoryKafkaListener(InventoryService service, ObjectMapper mapper,
            @Value("${pueblo.inventory.command-secret:}") String secret) {
        this.service = service;
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32) throw new IllegalArgumentException("Inventory command secret requires at least 32 UTF-8 bytes");
    }

    @KafkaListener(id = "inventory-commands", topics = COMMANDS_TOPIC, groupId = "catalog-inventory-v1")
    public void receive(ConsumerRecord<String, String> record) {
        if (record.value() == null || record.value().length() > 65536) throw invalid();
        byte[] body = record.value().getBytes(StandardCharsets.UTF_8);
        if (body.length > 65536) throw invalid();
        var headers = record.headers().headers(SIGNATURE_HEADER).iterator();
        if (!headers.hasNext()) throw invalid();
        byte[] provided = headers.next().value();
        if (provided == null || provided.length != 64 || headers.hasNext()) throw invalid();
        try {
            byte[] expected = signature(body, secret);
            if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(new String(provided, StandardCharsets.US_ASCII)))) throw invalid();
            EventEnvelope envelope = mapper.readValue(body, EventEnvelope.class);
            InventoryCommand command = mapper.treeToValue(envelope.payload(), InventoryCommand.class);
            if (!envelope.producer().equals("order-service") || envelope.schemaVersion() != 1
                    || !envelope.eventType().equals("InventoryCommand") || command.type() == InventoryCommand.Type.EXPIRE
                    || !envelope.eventId().equals(command.commandId()) || !envelope.aggregateId().equals(command.orderId().toString())
                    || !command.orderId().toString().equals(record.key())) throw invalid();
            service.execute(command); // Returns only after state, receipt and result outbox commit.
        } catch (java.io.IOException | IllegalArgumentException ex) {
            // Never echo the raw signed payload or credentials into logs.
            throw invalid();
        }
    }

    static byte[] signature(byte[] body, byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("HMAC unavailable", ex); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid authenticated inventory command"); }
}
