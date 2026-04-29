package io.github.dataaudit.ai.profile;

import io.github.dataaudit.ai.model.TableProfile;
import io.github.dataaudit.spi.model.TaskFileSpec;

public interface ProfileBuilder {
    TableProfile build(TaskFileSpec spec);
}
