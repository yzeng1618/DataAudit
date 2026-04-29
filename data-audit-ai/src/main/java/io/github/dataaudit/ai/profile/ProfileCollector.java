package io.github.dataaudit.ai.profile;

import io.github.dataaudit.ai.model.TableProfile;
import io.github.dataaudit.spi.connector.ConnectorBundle;
import io.github.dataaudit.spi.model.ReadRequest;
import io.github.dataaudit.spi.model.SummaryMetrics;
import io.github.dataaudit.spi.model.TaskFileSpec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ProfileCollector {
    private final ProfileCollectionOptions options;
    private final ProfileEvidenceEnricher enricher;
    private final SampleMasker sampleMasker;

    public ProfileCollector() {
        this(new ProfileCollectionOptions(), new ProfileEvidenceEnricher(), new SampleMasker());
    }

    public ProfileCollector(ProfileCollectionOptions options,
                            ProfileEvidenceEnricher enricher,
                            SampleMasker sampleMasker) {
        this.options = options;
        this.enricher = enricher;
        this.sampleMasker = sampleMasker;
    }

    public TableProfile collect(TaskFileSpec spec, ConnectorOpener opener) {
        TableProfile profile = new TaskFileProfileBuilder(options).build(spec);
        collectEndpoint(spec, spec.source, profile, "source", opener);
        collectEndpoint(spec, spec.target, profile, "target", opener);
        return profile;
    }

    private void collectEndpoint(TaskFileSpec spec,
                                 TaskFileSpec.EndpointSpec endpoint,
                                 TableProfile profile,
                                 String side,
                                 ConnectorOpener opener) {
        if (!hasEnoughConnectorConfig(endpoint, spec)) {
            profile.missingInformation.add(side + "_connector_evidence_skipped_missing_connection_config");
            return;
        }
        try (ConnectorBundle bundle = opener.open(spec, endpoint)) {
            if (bundle == null) {
                profile.missingInformation.add(side + "_connector_evidence_unavailable:null_bundle");
                return;
            }
            SummaryMetrics summary = readSummary(spec, bundle, profile, side);
            enricher.merge(
                    profile,
                    side,
                    bundle.getSchemaReader() == null ? null : bundle.getSchemaReader().readSchema(),
                    bundle.getMetadataReader() == null ? null : bundle.getMetadataReader().readMetadata(spec.boundary),
                    summary);
            if (profile.evidence.stream().anyMatch(e -> e.endsWith("_schema") || e.endsWith("_metadata"))) {
                profile.missingInformation.remove("connector_schema_metadata_not_collected");
            }
            if (summary != null) {
                profile.missingInformation.remove("live_stats_sample_collection_not_executed");
            }
            collectSamples(spec, bundle, profile, side);
        } catch (Exception e) {
            profile.missingInformation.add(side + "_connector_evidence_unavailable:" + e.getClass().getSimpleName());
        }
    }

    private SummaryMetrics readSummary(TaskFileSpec spec, ConnectorBundle bundle, TableProfile profile, String side) {
        if (bundle.getSignalReader() == null) {
            profile.missingInformation.add(side + "_signal_summary_not_available");
            return null;
        }
        try {
            return bundle.getSignalReader().readSummary(readRequest(spec, null));
        } catch (Exception e) {
            profile.missingInformation.add(side + "_signal_summary_unavailable:" + e.getClass().getSimpleName());
            return null;
        }
    }

    private void collectSamples(TaskFileSpec spec, ConnectorBundle bundle, TableProfile profile, String side) {
        if (bundle.getRowStreamReader() == null) {
            profile.missingInformation.add(side + "_sample_collection_not_available");
            return;
        }
        List<TableProfile.SampleProfile> samples = new ArrayList<>();
        long deadlineNanos = System.nanoTime() + Duration.ofMillis(options.timeoutMillis).toNanos();
        try {
            bundle.getRowStreamReader().scanRows(readRequest(spec, (long) options.maxSampleRows), row -> {
                if (System.nanoTime() > deadlineNanos) {
                    throw new CollectionLimitReachedException();
                }
                for (String field : sampleFields(spec, row)) {
                    samples.add(sampleMasker.mask(field, row.get(field)));
                    if (samples.size() >= options.maxSampleFields) {
                        throw new CollectionLimitReachedException();
                    }
                }
                throw new CollectionLimitReachedException();
            });
        } catch (CollectionLimitReachedException ignored) {
            profile.missingInformation.add(side + "_sample_collection_limited");
        } catch (Exception e) {
            profile.missingInformation.add(side + "_sample_collection_unavailable:" + e.getClass().getSimpleName());
        }
        if (!samples.isEmpty()) {
            profile.samples.clear();
            profile.samples.addAll(samples);
            addEvidence(profile, side + "_masked_samples");
            profile.missingInformation.remove("live_stats_sample_collection_not_executed");
        }
    }

    private ReadRequest readRequest(TaskFileSpec spec, Long limit) {
        ReadRequest request = new ReadRequest();
        request.columns.addAll(spec.object.columns);
        request.boundaryType = spec.boundary.type;
        request.boundaryReference = spec.boundary.reference;
        request.limit = limit;
        return request;
    }

    private List<String> sampleFields(TaskFileSpec spec, Map<String, Object> row) {
        Set<String> fields = new LinkedHashSet<>();
        fields.addAll(spec.object.columns);
        fields.addAll(row.keySet());
        return fields.stream().filter(row::containsKey).limit(options.maxSampleFields).toList();
    }

    private void addEvidence(TableProfile profile, String evidence) {
        if (!profile.evidence.contains(evidence)) {
            profile.evidence.add(evidence);
        }
    }

    private boolean hasEnoughConnectorConfig(TaskFileSpec.EndpointSpec endpoint, TaskFileSpec spec) {
        if (endpoint == null || endpoint.type == null || endpoint.type.isBlank()) {
            return false;
        }
        String type = endpoint.type.toLowerCase();
        if ("jdbc".equals(type)) {
            return endpoint.url != null && !endpoint.url.isBlank();
        }
        if ("trino".equals(type) || "sql".equals(type)) {
            return spec.queryConnector != null
                    && spec.queryConnector.uri != null
                    && !spec.queryConnector.uri.isBlank();
        }
        if ("iceberg".equals(type)) {
            return endpoint.warehouse != null && !endpoint.warehouse.isBlank()
                    && endpoint.table != null && !endpoint.table.isBlank();
        }
        return true;
    }

    @FunctionalInterface
    public interface ConnectorOpener {
        ConnectorBundle open(TaskFileSpec spec, TaskFileSpec.EndpointSpec endpoint) throws Exception;
    }

    private static class CollectionLimitReachedException extends RuntimeException {
    }
}
