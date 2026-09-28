package com.github.lisama569.secretleasebrokerjava.web;

import com.github.lisama569.secretleasebrokerjava.model.Lease;
import com.github.lisama569.secretleasebrokerjava.model.SecretVersion;
import com.github.lisama569.secretleasebrokerjava.service.LeaseService;
import com.github.lisama569.secretleasebrokerjava.store.SecretStore;
import com.github.lisama569.secretleasebrokerjava.web.dto.Dtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租约面 REST 接口。
 */
@RestController
@RequestMapping("/api/leases")
public class LeaseController {

    private final LeaseService leaseService;
    private final SecretStore secretStore;

    public LeaseController(LeaseService leaseService, SecretStore secretStore) {
        this.leaseService = leaseService;
        this.secretStore = secretStore;
    }

    @PostMapping
    public Dtos.LeaseView issue(@RequestBody Dtos.IssueRequest request) {
        Lease lease = leaseService.issue(request.callerId(), request.secretPath(),
                request.ttlMillis());
        return toView(lease);
    }

    @PostMapping("/{leaseId}/renew")
    public Dtos.LeaseView renew(@PathVariable String leaseId,
                                @RequestBody(required = false) Dtos.RenewRequest request) {
        Long ttl = request == null ? null : request.ttlMillis();
        return toView(leaseService.renew(leaseId, ttl));
    }

    @PostMapping("/{leaseId}/revoke")
    public Dtos.RevokeResponse revoke(@PathVariable String leaseId) {
        Lease lease = leaseService.revoke(leaseId);
        return new Dtos.RevokeResponse(lease.id(), lease.status().name(),
                lease.revokedAt() == null ? 0L : lease.revokedAt());
    }

    @GetMapping("/{leaseId}")
    public Dtos.LeaseView get(@PathVariable String leaseId) {
        return toView(leaseService.getLease(leaseId));
    }

    /** 持有效租约取用其绑定版本的密钥材料。 */
    @PostMapping("/{leaseId}/material")
    public Dtos.MaterialResponse material(@PathVariable String leaseId) {
        SecretVersion delivered = leaseService.fetchMaterial(leaseId);
        int latest = secretStore.findLatest(delivered.secretPath())
                .map(SecretVersion::version).orElse(delivered.version());
        return new Dtos.MaterialResponse(leaseId, delivered.secretPath(), delivered.version(),
                latest, delivered.material());
    }

    static Dtos.LeaseView toView(Lease lease) {
        return new Dtos.LeaseView(lease.id(), lease.callerId(), lease.secretPath(),
                lease.secretVersion(), lease.issuedAt(), lease.expiresAt(),
                lease.renewCount(), lease.status().name());
    }
}
