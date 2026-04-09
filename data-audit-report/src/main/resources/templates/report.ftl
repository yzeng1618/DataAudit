<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>data-audit report</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 24px; color: #222; }
        h1, h2 { margin-bottom: 8px; }
        table { border-collapse: collapse; width: 100%; margin-bottom: 24px; }
        th, td { border: 1px solid #ddd; padding: 8px; text-align: left; vertical-align: top; }
        th { background: #f5f5f5; }
        code { background: #f4f4f4; padding: 2px 4px; }
        .status { font-weight: bold; }
    </style>
</head>
<body>
<h1>data-audit report</h1>
<p class="status">status: ${report.result.status}</p>
<p>runId: ${report.run_id!""}</p>
<p>task: ${report.plan.task_name!""}</p>
<p>scaleClass: ${report.plan.scale_class!""}</p>
<p>signalStrategy: ${report.plan.signal_strategy!""}</p>
<p>localizationStrategy: ${report.plan.localization_strategy!""}</p>
<p>rootCause: ${report.result.root_cause!""}</p>
<p>proofMode: ${report.result.proof_mode!""}</p>
<p>confidence: ${report.result.confidence!""}</p>
<p>noKeyMode: ${report.result.no_key_mode?c}</p>
<p>fallbackReason: ${report.result.fallback_reason!""}</p>
<p>resumeHint: <code>${report.result.resume_hint!""}</code></p>

<h2>Boundary</h2>
<table>
    <tr><th>type</th><td>${report.plan.boundary.type!""}</td></tr>
    <tr><th>reference</th><td>${report.plan.boundary.reference!""}</td></tr>
    <tr><th>fingerprint</th><td>${report.plan.boundary.fingerprint!""}</td></tr>
    <tr><th>stable</th><td>${report.plan.boundary.stable?c}</td></tr>
    <tr><th>detail</th><td>${report.plan.boundary.detail!""}</td></tr>
</table>

<h2>Evidence</h2>
<table>
    <tr><th>source row count</th><td>${(report.evidence.global_signal.source_summary.row_count)!""}</td></tr>
    <tr><th>source checksum</th><td>${(report.evidence.global_signal.source_summary.checksum)!""}</td></tr>
    <tr><th>target row count</th><td>${(report.evidence.global_signal.target_summary.row_count)!""}</td></tr>
    <tr><th>target checksum</th><td>${(report.evidence.global_signal.target_summary.checksum)!""}</td></tr>
    <tr><th>localization strategy</th><td>${(report.evidence.localization.strategy)!""}</td></tr>
    <tr><th>exact diff completed</th><td>${(report.evidence.exact_diff.completed)?c}</td></tr>
    <tr><th>sample mode</th><td>${(report.result.sampling_summary.mode)!""}</td></tr>
    <tr><th>sample column</th><td>${(report.result.sampling_summary.sample_column)!""}</td></tr>
    <tr><th>sample source rows</th><td>${(report.result.sampling_summary.source_rows)!""}</td></tr>
    <tr><th>sample target rows</th><td>${(report.result.sampling_summary.target_rows)!""}</td></tr>
</table>

<h2>Decision Trace</h2>
<table>
    <tr><th>plan</th><td><#list report.plan.decision_trace as trace><div>${trace}</div></#list></td></tr>
    <tr><th>notes</th><td><#list report.evidence.notes as note><div>${note}</div></#list></td></tr>
</table>

<h2>Suspect Scopes</h2>
<table>
    <tr>
        <th>sliceKey</th>
        <th>sliceType</th>
        <th>rowEstimate</th>
        <th>drilldownable</th>
        <th>reason</th>
    </tr>
    <#list report.result.suspect_slices as slice>
        <tr>
            <td>${slice.slice_key!""}</td>
            <td>${slice.slice_type!""}</td>
            <td>${slice.row_estimate!""}</td>
            <td>${slice.drilldownable?c}</td>
            <td>${slice.reason!""}</td>
        </tr>
    </#list>
</table>

<h2>Diff Samples</h2>
<table>
    <tr>
        <th>type</th>
        <th>key</th>
        <th>source</th>
        <th>target</th>
        <th>slice</th>
    </tr>
    <#list report.result.diff.samples as sample>
        <tr>
            <td>${sample.type!""}</td>
            <td>${sample.key!""}</td>
            <td>${sample.source_value!""}</td>
            <td>${sample.target_value!""}</td>
            <td>${sample.slice_key!""}</td>
        </tr>
    </#list>
</table>
</body>
</html>
