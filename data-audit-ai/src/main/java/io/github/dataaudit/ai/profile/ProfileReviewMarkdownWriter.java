// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.ai.profile;

import io.github.dataaudit.ai.model.ProfileReview;

public class ProfileReviewMarkdownWriter {
    public String render(ProfileReview review) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Profile Review\n\n");
        builder.append("- Profile status: ").append(review.status).append("\n");
        builder.append("- Evidence quality: ").append(review.evidenceQuality).append("\n\n");
        if (!review.confirmationItems.isEmpty()) {
            builder.append("## Need confirmation\n\n");
            for (ProfileReview.ConfirmationItem item : review.confirmationItems) {
                builder.append("- ").append(item.field)
                        .append(": ").append(item.suggestedValue)
                        .append(", confidence=").append(String.format("%.2f", item.confidence))
                        .append("\n");
                builder.append("  - evidence: ").append(String.join("; ", item.evidence)).append("\n");
                builder.append("  - missing_information: ")
                        .append(String.join("; ", item.missingInformation)).append("\n");
                builder.append("  - impact: ").append(item.impact).append("\n");
            }
            builder.append("\n");
        }
        if (!review.missingInformation.isEmpty()) {
            builder.append("## Missing information\n\n");
            for (String missing : review.missingInformation) {
                builder.append("- ").append(missing).append("\n");
            }
            builder.append("\n");
        }
        builder.append("## Next\n\n");
        for (String action : review.nextActions) {
            builder.append("- ").append(action).append("\n");
        }
        return builder.toString();
    }
}
