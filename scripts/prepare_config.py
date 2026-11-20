#!/usr/bin/env python3
"""
Downloads the latest Serverless-oob.jsonc from patterniha/Serverless-for-Iran,
strips JSONC comments safely, and merges PattNG's Iran direct-bypass routing rules
without disturbing Serverless OOB outbounds or internal anti-DPI DoH resolvers.
"""
import json
import os
import re
import sys
import urllib.request

SERVERLESS_OOB_URL = (
    "https://raw.githubusercontent.com/patterniha/Serverless-for-Iran/main/Serverless-oob.jsonc"
)
PATTNG_IRAN_ROUTING_URL = (
    "https://raw.githubusercontent.com/patterniha/PattNG/master/V2rayNG/app/src/main/assets/custom_routing_white_iran"
)


def fetch_text(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "DirectAccessBuilder/1.0"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read().decode("utf-8")


def strip_jsonc(text: str) -> str:
    pattern = re.compile(r'("(?:\\.|[^"\\])*")|//[^\n]*|/\*[\s\S]*?\*/', re.MULTILINE)

    def replacer(match: re.Match) -> str:
        return match.group(1) if match.group(1) is not None else ""

    no_comments = pattern.sub(replacer, text)
    return re.sub(r",(\s*[}\]])", r"\1", no_comments)


def merge_config(config: dict, pattng_rules: list | None) -> dict:
    # 1. Lock inbound on 127.0.0.1:10808 (required for hev-socks5-tunnel)
    inbounds = config.get("inbounds", [])
    user_inbound_tag = "mixed-in"
    found_10808 = False
    for ib in inbounds:
        if ib.get("port") == 10808:
            ib["listen"] = "127.0.0.1"
            if ib.get("protocol") in ("socks", "mixed"):
                ib.setdefault("settings", {})["udp"] = True
            user_inbound_tag = ib.get("tag") or user_inbound_tag
            ib["tag"] = user_inbound_tag
            found_10808 = True
            break

    if not found_10808:
        inbounds.insert(
            0,
            {
                "tag": user_inbound_tag,
                "listen": "127.0.0.1",
                "port": 10808,
                "protocol": "mixed",
                "sniffing": {
                    "enabled": True,
                    "destOverride": ["fakedns", "tls", "http", "quic"],
                    "routeOnly": False,
                },
                "settings": {"udp": True},
            },
        )
    config["inbounds"] = inbounds

    # 2. Ensure dedicated pure-direct freedom outbound for Iranian traffic
    outbounds = config.get("outbounds", [])
    iran_direct_tag = "iran-direct"
    if not any(ob.get("tag") == iran_direct_tag for ob in outbounds):
        outbounds.append(
            {
                "tag": iran_direct_tag,
                "protocol": "freedom",
                "settings": {"domainStrategy": "UseIP"},
            }
        )
    config["outbounds"] = outbounds

    # 3. Minimal logging for battery, RAM, and privacy
    config.setdefault("log", {})["loglevel"] = "warning"

    # 4. Ensure domestic-dns also resolves geosite:ir (Chocolate4U list) locally
    dns_cfg = config.get("dns", {})
    for srv in dns_cfg.get("servers", []):
        if isinstance(srv, dict) and srv.get("tag") == "domestic-dns":
            doms = srv.setdefault("domains", [])
            for required in ("domain:ir", "geosite:ir", "geosite:category-ir", "geosite:private"):
                if required not in doms:
                    doms.append(required)

    # 5. Collect PattNG Iran whitelist direct rules safely
    iran_domains = ["domain:ir", "geosite:ir", "geosite:category-ir", "geosite:private"]
    iran_ips = ["geoip:ir", "geoip:private"]

    if pattng_rules:
        for rule in pattng_rules:
            if not isinstance(rule, dict) or rule.get("outboundTag") != "direct":
                continue
            for d in rule.get("domain", []):
                if isinstance(d, str) and not d.startswith("ext:") and d not in iran_domains:
                    iran_domains.append(d)
            for ip in rule.get("ip", []):
                if isinstance(ip, str) and not ip.startswith("ext:") and ip not in iran_ips:
                    iran_ips.append(ip)

    # 6. Insert Iran bypass rules AFTER DNS-intercept & internal DNS rules,
    #    scoped strictly to user_inbound_tag so Serverless DoH/OOB rules stay intact.
    routing = config.setdefault("routing", {})
    routing.setdefault("domainStrategy", "IPIfNonMatch")
    existing_rules = routing.get("rules", [])

    prefix_rules = []
    suffix_rules = []
    for r in existing_rules:
        is_dns_rule = (
            r.get("outboundTag") in ("dns-out", "dns")
            or r.get("port") == 53
            or str(r.get("port", "")) == "53"
            or (
                isinstance(r.get("inboundTag"), list)
                and user_inbound_tag not in r.get("inboundTag", [])
            )
        )
        if is_dns_rule:
            prefix_rules.append(r)
        else:
            suffix_rules.append(r)

    iran_domain_rule = {
        "type": "field",
        "inboundTag": [user_inbound_tag],
        "domain": iran_domains,
        "outboundTag": iran_direct_tag,
    }
    iran_ip_rule = {
        "type": "field",
        "inboundTag": [user_inbound_tag],
        "ip": iran_ips,
        "outboundTag": iran_direct_tag,
    }

    routing["rules"] = prefix_rules + [iran_domain_rule, iran_ip_rule] + suffix_rules
    return config


def main() -> None:
    out_path = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/config.json"
    os.makedirs(os.path.dirname(out_path), exist_ok=True)

    print("Fetching latest Serverless-oob.jsonc from patterniha/Serverless-for-Iran...")
    raw_oob = fetch_text(SERVERLESS_OOB_URL)
    config = json.loads(strip_jsonc(raw_oob))

    pattng_rules = None
    try:
        print("Fetching PattNG custom_routing_white_iran...")
        raw_pattng = fetch_text(PATTNG_IRAN_ROUTING_URL)
        parsed = json.loads(strip_jsonc(raw_pattng))
        pattng_rules = parsed if isinstance(parsed, list) else parsed.get("rules", [])
    except Exception as exc:
        print(f"Warning: Could not fetch PattNG custom_routing_white_iran ({exc}); using built-in IR geo rules.")

    final_cfg = merge_config(config, pattng_rules)

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(final_cfg, f, separators=(",", ":"), ensure_ascii=False)

    print(f"Successfully wrote minified config to {out_path} ({os.path.getsize(out_path)} bytes)")


if __name__ == "__main__":
    main()
