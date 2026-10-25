#!/usr/bin/env python3
"""Offline render and security invariants. Requires kubectl and PyYAML; never contacts a cluster."""
import json
from pathlib import Path
import subprocess
import sys
import yaml

root = Path(__file__).resolve().parents[1]
rendered = subprocess.check_output(["kubectl", "kustomize", str(root / "infra/k8s/base")], text=True)
resources = list(yaml.safe_load_all(rendered))
expected = {f"{name}-service" for name in ("member", "catalog", "order", "payment")}

def require(condition, message):
    if not condition:
        sys.exit("FAIL: " + message)

def objects(kind):
    return {r["metadata"]["name"]: r for r in resources if r["kind"] == kind}

namespace = objects("Namespace")["pueblo"]
require(namespace["metadata"]["labels"].get("istio-injection") == "enabled", "sidecar injection")
require("istio.io/dataplane-mode" not in namespace["metadata"]["labels"], "do not mix ambient enrollment")
policy = objects("PeerAuthentication")["default"]["spec"]
require(policy == {"mtls": {"mode": "STRICT"}}, "namespace STRICT without port exceptions")
require(set(objects("ServiceAccount")) == expected, "dedicated workload identities")
require(set(objects("Service")) == expected, "four backend services")
require(set(objects("Deployment")) == expected, "four backend deployments")
for resource in resources:
    if resource["kind"] != "Namespace":
        require(resource["metadata"].get("namespace") == "pueblo", "resource namespace")
for name, deployment in objects("Deployment").items():
    template = deployment["spec"]["template"]
    metadata, spec = template["metadata"], template["spec"]
    require(spec["serviceAccountName"] == name, name + " identity")
    require(metadata["labels"].get("sidecar.istio.io/inject") == "true", name + " injection")
    annotations = metadata["annotations"]
    require(annotations.get("sidecar.istio.io/rewriteAppHTTPProbers") == "true", name + " probes")
    require(not any("excludeInboundPorts" in k or "excludeOutboundPorts" in k for k in annotations), name + " no bypass")
    require(json.loads(annotations["proxy.istio.io/config"])["holdApplicationUntilProxyStarts"], name + " proxy startup")
    require(not spec.get("hostNetwork", False), name + " no host networking")
    app = spec["containers"][0]
    require(app["ports"] == [{"containerPort": 8080, "name": "http"}], name + " application HTTP")
    require(app["envFrom"] == [{"secretRef": {"name": name + "-runtime"}}], name + " runtime secret")
    require(app["securityContext"]["runAsNonRoot"], name + " non-root application")
    service = objects("Service")[name]["spec"]
    require(service["type"] == "ClusterIP", name + " internal service exposure")
    require(service["selector"] == deployment["spec"]["selector"]["matchLabels"], name + " selector")
    require(service["ports"] == [{"appProtocol": "http", "name": "http", "port": 8080, "targetPort": "http"}], name + " service port")
    for probe in ("startupProbe", "readinessProbe", "livenessProbe"):
        require(app[probe]["httpGet"]["port"] == "http", name + " " + probe)
member = objects("Deployment")["member-service"]["spec"]["template"]["spec"]
require(member["volumes"][0]["secret"]["secretName"] == "member-auth-keys", "member signing and mail key secret")
require(member["containers"][0]["volumeMounts"][0]["readOnly"], "member keys read only")
require(member["securityContext"]["fsGroup"] == 10001, "member keys readable by application group")
rules = objects("DestinationRule")
require(len(rules) == 4, "four scoped TLS rules")
require({r["spec"]["host"] for r in rules.values()} == {f"{n}.pueblo.svc.cluster.local" for n in expected}, "TLS targets")
for rule in rules.values():
    require(rule["spec"]["trafficPolicy"]["tls"] == {"mode": "ISTIO_MUTUAL"}, "mesh TLS origination")
print(f"OK: rendered {len(resources)} resources; namespace STRICT, four identities, probes and mesh TLS targets")
print("Static validation only; Istio CRD validation, injection and TLS handshake require a running cluster.")
