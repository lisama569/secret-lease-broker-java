package com.github.lisama569.secretleasebrokerjava.web.dto;

public record LeaseResponse(String leaseId,
                            String callerId,
                            String path,
                            long secretVersion,
                            long issuedAtMillis,
                            long expiresAtMillis,
                            long timeToLiveMillis,
                            String status,
                            int renewCount,
                            Long revokedAtMillis,
                            String revokedReason,
                            long revision) {
}
