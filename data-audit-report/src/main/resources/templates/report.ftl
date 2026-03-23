<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>data-audit report</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 24px; color: #222; }
        h1, h2 { margin-bottom: 8px; }
        table { border-collapse: collapse; width: 100%; margin-bottom: 24px; }
        th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
        th { background: #f5f5f5; }
        code { background: #f4f4f4; padding: 2px 4px; }
        .status { font-weight: bold; }
    </style>
</head>
<body>
<h1>data-audit report</h1>
<p class="status">status: ${report.result.status}</p>
<p>runId: ${report.run_id!""}</p>
<p>objectClass: ${report.plan.object_class!""}</p>
<p>selectedPath: ${report.plan.selected_path!""}</p>
<p>signalBackend: ${report.plan.signal_backend!""}</p>
<p>signalStrategy: ${report.plan.signal_strategy!""}</p>
<p>localizationStrategy: ${report.plan.localization_strategy!""}</p>
<p>reason: ${report.plan.reason!""}</p>
<p>rootCause: ${report.result.root_cause!""}</p>
<p>consistencyLevel: ${report.result.consistency_level!""}</p>
<p>verdictBasis: ${report.result.verdict_basis!""}</p>
<p>inconclusiveReason: ${report.result.inconclusive_reason!""}</p>
<p>resumeHint: <code>${report.result.resume_hint!""}</code></p>

<h2>Decision Trace</h2>
<table>
    <tr><th>stage</th><th>trace</th></tr>
    <tr>
        <td>plan</td>
        <td>
            <#list report.plan.decision_trace as trace>
                <div>${trace}</div>
            </#list>
        </td>
    </tr>
    <tr>
        <td>result</td>
        <td>
            <#list report.result.decision_trace as trace>
                <div>${trace}</div>
            </#list>
        </td>
    </tr>
</table>

<h2>DML / DDL Audit</h2>
<table>
    <tr><th>DML verdict</th><td>${report.result.dml_audit.verdict!""}</td></tr>
    <tr><th>insert</th><td>${report.result.dml_audit.insert_strategy!""}</td></tr>
    <tr><th>update</th><td>${report.result.dml_audit.update_strategy!""}</td></tr>
    <tr><th>delete</th><td>${report.result.dml_audit.delete_strategy!""}</td></tr>
    <tr><th>merge</th><td>${report.result.dml_audit.merge_strategy!""}</td></tr>
    <tr><th>DDL verdict</th><td>${report.result.ddl_audit.verdict!""}</td></tr>
    <tr><th>DDL mode</th><td>${report.result.ddl_audit.mode!""}</td></tr>
    <tr><th>partition evolution</th><td>${report.result.ddl_audit.partition_evolution!""}</td></tr>
</table>

<h2>Boundary</h2>
<table>
    <tr><th>type</th><td>${report.plan.boundary.type!""}</td></tr>
    <tr><th>reference</th><td>${report.plan.boundary.reference!""}</td></tr>
    <tr><th>fingerprint</th><td>${report.plan.boundary.fingerprint!""}</td></tr>
</table>

<h2>Suspect Slices</h2>
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
