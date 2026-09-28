package com.github.lisama569.secretleasebrokerjava.web.dto;

public record AuditEventResponse(long sequence,
                                 long timestampMillis,
                                 String type,
                                 String callerId,
                                 String path,
                                 String leaseId,
                                 String result,
                                 String detail) {
}
