package dev.vijay.gatehouse;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.*;

@Service
class Audit {
  private final JdbcTemplate db;

  Audit(JdbcTemplate db) {
    this.db = db;
  }

  void record(String actor, String event, String target, String detail) {
    var attributes = RequestContextHolder.getRequestAttributes();
    String ip =
        attributes instanceof ServletRequestAttributes request
            ? request.getRequest().getRemoteAddr()
            : "";
    db.update(
        "INSERT INTO iam_audit(id,actor,event,target,detail,ip) VALUES(?,?,?,?,?,?)",
        UUID.randomUUID(),
        shorten(actor, 254),
        event,
        shorten(target, 254),
        shorten(detail, 500),
        shorten(ip, 80));
  }

  private String shorten(String value, int length) {
    return value == null ? "" : value.substring(0, Math.min(value.length(), length));
  }
}
