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
<p>reason: ${report.plan.reason!""}</p>
<p>rootCause: ${report.result.root_cause!""}</p>
<p>resumeHint: <code>${report.result.resume_hint!""}</code></p>

<h2>Boundary</h2>
<table>
    <tr><th>type</th><td>${report.plan.boundary.type!""}</td></tr>
    <tr><th>reference</th><td>${report.plan.boundary.reference!""}</td></tr>
    <tr><th>fingerprint</th><td>${report.plan.boundary.fingerprint!""}</td></tr>
</table>

<h2>Suspect Segments</h2>
<table>
    <tr>
        <th>segmentKey</th>
        <th>reason</th>
        <th>sourceDigest</th>
        <th>targetDigest</th>
    </tr>
    <#list report.result.suspect_segments as segment>
        <tr>
            <td>${segment.segment_key!""}</td>
            <td>${segment.reason!""}</td>
            <td>${segment.source_digest!""}</td>
            <td>${segment.target_digest!""}</td>
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
        <th>segment</th>
    </tr>
    <#list report.result.diff.samples as sample>
        <tr>
            <td>${sample.type!""}</td>
            <td>${sample.key!""}</td>
            <td>${sample.source_value!""}</td>
            <td>${sample.target_value!""}</td>
            <td>${sample.segment_key!""}</td>
        </tr>
    </#list>
</table>
</body>
</html>
