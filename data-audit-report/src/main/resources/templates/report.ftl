<#ftl output_format="HTML" auto_esc=true>
<#assign status = (report.result.status)!"UNKNOWN">
<#assign statusClass = (status == "CONSISTENT")?then("ok", (status == "DIFF_FOUND")?then("bad", "warn"))>
<#assign samples = (report.result.diff.samples)![]>
<#assign slices = (report.result.suspect_slices)![]>
<#assign valueMode = (report.evidence_value_mode)!"masked">
<#assign srcRows = ((report.result.source_summary.row_count)?has_content)?then(report.result.source_summary.row_count, (report.evidence.global_signal.source_summary.row_count)!"-")>
<#assign tgtRows = ((report.result.target_summary.row_count)?has_content)?then(report.result.target_summary.row_count, (report.evidence.global_signal.target_summary.row_count)!"-")>
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>data-audit · ${(report.plan.task_name)!"report"}</title>
<style>
  :root{
    --ink:#1a2330; --body:#3d4854; --muted:#71808e; --line:#dde3e9;
    --paper:#f4f6f8; --card:#ffffff;
    --ok:#1f7a45; --ok-bg:#e7f3ec;
    --bad:#b3362a; --bad-bg:#faeae7;
    --warn:#95660f; --warn-bg:#f8efdb;
    --mono:ui-monospace,"SF Mono",Consolas,Menlo,monospace;
  }
  *{box-sizing:border-box}
  body{margin:0;background:var(--paper);color:var(--body);
    font:15px/1.65 -apple-system,"Segoe UI",Roboto,"PingFang SC","Microsoft YaHei",sans-serif;
    -webkit-print-color-adjust:exact;print-color-adjust:exact}
  .page{max-width:960px;margin:0 auto;padding:34px 28px 60px}
  h1{font-size:15px;font-weight:600;letter-spacing:.08em;text-transform:uppercase;color:var(--muted);margin:0}
  h2{font-size:16px;color:var(--ink);margin:34px 0 10px}
  .verdict{margin-top:14px;background:var(--card);border:1px solid var(--line);border-radius:8px;
    padding:22px 26px;box-shadow:0 1px 3px rgba(26,35,48,.06)}
  .verdict .status{font-size:30px;font-weight:800;letter-spacing:-.01em}
  .verdict.ok .status{color:var(--ok)} .verdict.bad .status{color:var(--bad)} .verdict.warn .status{color:var(--warn)}
  .verdict .sentence{margin:6px 0 0;font-size:15.5px;color:var(--body)}
  .facts{display:flex;flex-wrap:wrap;gap:26px;margin-top:18px;padding-top:16px;border-top:1px solid var(--line)}
  .fact .num{font-size:21px;font-weight:700;color:var(--ink);font-variant-numeric:tabular-nums}
  .fact .lbl{font-size:12px;color:var(--muted)}
  .meta{margin-top:10px;font-size:12.5px;color:var(--muted)}
  .meta code{font-family:var(--mono);font-size:12px;background:var(--paper);padding:1px 5px;border-radius:3px}
  .note{margin-top:14px;font-size:13px;color:var(--warn);background:var(--warn-bg);
    border:1px solid var(--warn);border-radius:6px;padding:8px 12px}
  .tablewrap{overflow-x:auto;background:var(--card);border:1px solid var(--line);border-radius:8px}
  table{border-collapse:collapse;width:100%;font-size:13.5px}
  th{background:var(--paper);color:var(--muted);font-size:11.5px;text-transform:uppercase;letter-spacing:.06em;text-align:left}
  th,td{border-bottom:1px solid var(--line);padding:8px 12px;vertical-align:top}
  tr:last-child td{border-bottom:none}
  td.mono, .mono{font-family:var(--mono);font-size:12.5px;word-break:break-all}
  .tag{display:inline-block;font-size:11.5px;font-weight:600;border-radius:4px;padding:1px 8px}
  .tag.bad{color:var(--bad);background:var(--bad-bg)}
  .empty{color:var(--muted);font-size:13.5px;padding:14px 16px}
  details{margin-top:12px;background:var(--card);border:1px solid var(--line);border-radius:8px;padding:12px 16px}
  summary{cursor:pointer;font-size:13.5px;color:var(--muted);font-weight:600}
  details div{font-family:var(--mono);font-size:12.5px;padding:3px 0;color:var(--body)}
  kv{display:block}
  .kvgrid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:1px;background:var(--line);
    border:1px solid var(--line);border-radius:8px;overflow:hidden}
  .kv{background:var(--card);padding:9px 14px}
  .kv .k{font-size:11.5px;color:var(--muted);text-transform:uppercase;letter-spacing:.05em}
  .kv .v{font-size:13.5px;color:var(--ink);font-family:var(--mono);word-break:break-all}
  footer{margin-top:44px;font-size:12px;color:var(--muted);border-top:1px solid var(--line);padding-top:12px}
  @media print{.page{padding:0} body{background:#fff}}
</style>
</head>
<body>
<div class="page">

<h1>data-audit report</h1>

<div class="verdict ${statusClass}">
  <div class="status">${status}</div>
  <p class="sentence">
    <#if status == "CONSISTENT">
      Source and target match on this boundary. Proof: ${(report.result.proof_mode)!"-"}, confidence ${(report.result.confidence)!"-"}.
    <#elseif status == "DIFF_FOUND">
      Differences were found<#if samples?size gt 0> — the sampled rows below show exactly what differs</#if>. Root cause: ${(report.result.root_cause)!"-"}.
    <#else>
      The audit did not complete normally (${(report.result.root_cause)!(report.result.fallback_reason)!"see details below"}).
    </#if>
  </p>
  <div class="facts">
    <div class="fact"><div class="num">${srcRows}</div><div class="lbl">source rows</div></div>
    <div class="fact"><div class="num">${tgtRows}</div><div class="lbl">target rows</div></div>
    <div class="fact"><div class="num">${samples?size}</div><div class="lbl">sampled differences</div></div>
    <div class="fact"><div class="num">${slices?size}</div><div class="lbl">suspect scopes</div></div>
    <div class="fact"><div class="num">${(report.result.confidence)!"-"}</div><div class="lbl">confidence</div></div>
  </div>
  <p class="meta">
    task <code>${(report.plan.task_name)!"-"}</code> ·
    run <code>${(report.run_id)!"-"}</code> ·
    boundary <code>${(report.plan.boundary.type)!"-"}<#if (report.plan.boundary.reference)?has_content>=${report.plan.boundary.reference}</#if></code> ·
    generated ${(report.generated_at)!"-"}
  </p>
  <#if valueMode == "masked" || valueMode == "hash">
  <p class="note">Sample values are protected (value_mode=${valueMode}); row keys stay readable. Set <span class="mono">output.value_mode: raw</span> for local investigation.</p>
  </#if>
</div>

<h2>Differences (sampled)</h2>
<#if samples?size gt 0>
<div class="tablewrap">
<table>
  <tr><th>Key</th><th>Type</th><th>Source value</th><th>Target value</th><th>Slice</th></tr>
  <#list samples as sample>
  <tr>
    <td class="mono">${(sample.key)!"-"}</td>
    <td><span class="tag bad">${(sample.type)!"-"}</span></td>
    <td class="mono">${(sample.source_value)!""}</td>
    <td class="mono">${(sample.target_value)!""}</td>
    <td class="mono">${(sample.slice_key)!""}</td>
  </tr>
  </#list>
</table>
</div>
<#else>
<div class="tablewrap"><p class="empty">No row-level differences were sampled.</p></div>
</#if>

<h2>Suspect scopes</h2>
<#if slices?size gt 0>
<div class="tablewrap">
<table>
  <tr><th>Slice</th><th>Type</th><th>Row estimate</th><th>Drilldown</th><th>Reason</th></tr>
  <#list slices as slice>
  <tr>
    <td class="mono">${(slice.slice_key)!"-"}</td>
    <td>${(slice.slice_type)!"-"}</td>
    <td class="mono">${(slice.row_estimate)!"-"}</td>
    <td class="mono"><#if (slice.drilldownable)!false>data-audit diff --slice ${(slice.slice_key)!""}<#else>-</#if></td>
    <td>${(slice.reason)!""}</td>
  </tr>
  </#list>
</table>
</div>
<#else>
<div class="tablewrap"><p class="empty">No suspect scopes — the mismatch was resolved without localization, or none was needed.</p></div>
</#if>

<h2>How this was verified</h2>
<div class="kvgrid">
  <div class="kv"><div class="k">scale class</div><div class="v">${(report.plan.scale_class)!"-"}</div></div>
  <div class="kv"><div class="k">signal strategy</div><div class="v">${(report.plan.signal_strategy)!"-"}</div></div>
  <div class="kv"><div class="k">localization</div><div class="v">${(report.plan.localization_strategy)!"-"}</div></div>
  <div class="kv"><div class="k">proof mode</div><div class="v">${(report.result.proof_mode)!"-"}</div></div>
  <div class="kv"><div class="k">no-key mode</div><div class="v">${((report.result.no_key_mode)!false)?c}</div></div>
  <div class="kv"><div class="k">fallback reason</div><div class="v">${(report.result.fallback_reason)!"-"}</div></div>
  <div class="kv"><div class="k">source checksum</div><div class="v">${(report.evidence.global_signal.source_summary.checksum)!"-"}</div></div>
  <div class="kv"><div class="k">target checksum</div><div class="v">${(report.evidence.global_signal.target_summary.checksum)!"-"}</div></div>
  <div class="kv"><div class="k">boundary fingerprint</div><div class="v">${(report.plan.boundary.fingerprint)!"-"}</div></div>
  <div class="kv"><div class="k">boundary stable</div><div class="v">${((report.plan.boundary.stable)!true)?c}</div></div>
  <div class="kv"><div class="k">sample mode</div><div class="v">${(report.result.sampling_summary.mode)!"-"}</div></div>
  <div class="kv"><div class="k">resume hint</div><div class="v">${(report.result.resume_hint)!"-"}</div></div>
</div>

<details>
  <summary>Planner decision trace (${((report.plan.decision_trace)![])?size} steps)</summary>
  <#list (report.plan.decision_trace)![] as trace><div>${trace}</div></#list>
  <#list (report.evidence.notes)![] as note><div>note: ${note}</div></#list>
</details>

<footer>
  Generated by data-audit · deterministic verification, AI plays no part in this verdict ·
  evidence files: report.json, suspect_slices.csv, row_diff_sample.csv
</footer>

</div>
</body>
</html>
