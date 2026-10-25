package com.pueblo.member.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuthRepository {
    public record User(String id, String email, String passwordHash, String nickname, String status, String role) { }
    public record Session(String id, String userId, Instant expiresAt, Instant revokedAt, String userStatus, String role) { }
    private final JdbcTemplate jdbc;
    public AuthRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<User> byEmail(String email) {
        return jdbc.query("SELECT * FROM member_user WHERE email = ?", this::user, email).stream().findFirst();
    }
    public Optional<User> byId(String id, boolean lock) {
        return jdbc.query("SELECT * FROM member_user WHERE id = ?" + (lock ? " FOR UPDATE" : ""), this::user, id).stream().findFirst();
    }
    public Optional<Session> session(String id, boolean lock) {
        return jdbc.query("""
                SELECT s.*, u.status AS user_status, u.role FROM auth_session s
                JOIN member_user u ON u.id = s.user_id WHERE s.id = ?
                """ + (lock ? " FOR UPDATE" : ""), (rs, n) -> new Session(rs.getString("id"),
                rs.getString("user_id"), time(rs, "expires_at"), time(rs, "revoked_at"),
                rs.getString("user_status"), rs.getString("role")), id).stream().findFirst();
    }
    private User user(ResultSet rs, int n) throws SQLException {
        return new User(rs.getString("id"), rs.getString("email"), rs.getString("password_hash"),
                rs.getString("nickname"), rs.getString("status"), rs.getString("role"));
    }
    public static Instant time(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column, java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));
        return timestamp == null ? null : timestamp.toInstant();
    }
}
