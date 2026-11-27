package com.axonbase.server;

import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.cluster.RaftWire;
import com.axonbase.core.cluster.TransferResult;
import com.axonbase.core.cluster.VersionCompatibility;
import com.axonbase.core.security.Authz;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operação de um nó para atualizar um de cada vez ({@code /admin/v1/cluster/...}): saúde, drenagem, transferência de
 * liderança e verificação de versões. Veja {@code docs/operacao-e-resiliencia.md}.
 */
final class ClusterOpsApi {

    private static final Duration TRANSFER_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_BODY = 4_096;

    private ClusterOpsApi() {
    }

    static boolean handle(AxonServer srv, AdminV1Servlet api, String method, List<String> seg,
                          HttpServletRequest req, HttpServletResponse resp, AdminV1Servlet.Caller c)
            throws IOException {
        if (seg.size() != 2 || !seg.get(0).equals("cluster")) {
            return false;
        }
        switch (method + " " + seg.get(1)) {
            case "GET health" -> {
                api.needRoot(c, Authz.Role.VIEWER);
                AdminV1Servlet.write(resp, 200, health(srv));
            }
            case "GET drain" -> {
                api.needRoot(c, Authz.Role.VIEWER);
                AdminV1Servlet.write(resp, 200, drainStatus(srv.drain().status()));
            }
            case "POST drain" -> {
                api.needRoot(c, Authz.Role.ADMIN);
                AdminV1Servlet.write(resp, 202, drainStatus(srv.drain().start()));
            }
            case "DELETE drain" -> {
                api.needRoot(c, Authz.Role.ADMIN);
                AdminV1Servlet.write(resp, 200, drainStatus(srv.drain().undrain()));
            }
            case "POST transfer-leadership" -> {
                api.needRoot(c, Authz.Role.ADMIN);
                transfer(srv, req, resp);
            }
            case "GET upgrade-check" -> {
                api.needRoot(c, Authz.Role.VIEWER);
                upgradeCheck(req, resp);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static AxonValue drainStatus(NodeDrain.Status status) {
        return AdminJson.obj("state", status.state().name(), "leader", status.leader(), "reason", status.reason(),
            "millis", status.millis());
    }

    private static AxonValue health(AxonServer srv) {
        var status = srv.clusterStatus().status();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodeId", status.nodeId());
        out.put("version", BuildInfo.version());
        out.put("protocol", RaftWire.PROTOCOL_LEVEL);
        out.put("role", status.role().name());
        out.put("leader", status.leader());
        out.put("ready", status.ready() && !srv.drain().draining());
        out.put("drain", srv.drain().status().state().name());
        out.put("term", status.term());
        out.put("commitIndex", status.commitIndex());
        ClusterRuntime cluster = srv.clusterRuntime();
        List<Object> peers = new ArrayList<>();
        if (cluster != null && cluster.leading()) {
            for (ClusterRuntime.PeerInfo peer : cluster.peerInfos()) {
                peers.add(AdminJson.obj("node", peer.node(), "address", peer.address(), "lag", peer.lagEntries(),
                    "active", peer.active(),
                    "protocol", peer.active() ? cluster.protocolOf(address(peer.address())) : 0));
            }
        }
        out.put("peers", peers);
        return AdminJson.of(out);
    }

    private static InetSocketAddress address(String raw) {
        int split = raw.lastIndexOf(':');
        return new InetSocketAddress(raw.substring(0, split), Integer.parseInt(raw.substring(split + 1)));
    }

    private static void transfer(AxonServer srv, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        ClusterRuntime cluster = srv.clusterRuntime();
        if (cluster == null) {
            throw new AdminV1Servlet.ApiError(409, "CONFLICT", "este nó não participa de um cluster");
        }
        String body = new String(req.getInputStream().readNBytes(MAX_BODY), StandardCharsets.UTF_8).trim();
        String target = null;
        if (!body.isEmpty()) {
            AxonValue parsed = AxonJson.parseDocument(body);
            AxonValue wanted = parsed.isObject() ? parsed.asObject().get("target") : null;
            target = wanted != null && wanted.isString() ? wanted.asString() : null;
        }
        TransferResult result = cluster.transferLeadership(target, TRANSFER_TIMEOUT);
        AdminV1Servlet.write(resp, result.transferred() ? 200 : 409, AdminJson.obj("transferred",
            result.transferred(), "leader", result.leader(), "reason", result.reason(), "millis", result.millis()));
    }

    /** {@code ?target=1.0.0&members=n1=0.10.1,n2=0.10.1} */
    private static void upgradeCheck(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String target = req.getParameter("target");
        String members = req.getParameter("members");
        if (target == null || members == null) {
            throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", "informe target e members=nó=versão,...");
        }
        Map<String, String> versions = new LinkedHashMap<>();
        for (String pair : members.split(",")) {
            int equals = pair.indexOf('=');
            if (equals < 1) {
                throw new AdminV1Servlet.ApiError(400, "BAD_REQUEST", "membro sem versão: " + pair);
            }
            versions.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
        }
        VersionCompatibility.Check check = VersionCompatibility.check(versions, target);
        AdminV1Servlet.write(resp, 200, AdminJson.obj("allowed", check.allowed(), "problems", check.problems()));
    }
}
