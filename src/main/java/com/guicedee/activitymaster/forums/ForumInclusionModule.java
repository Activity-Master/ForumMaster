package com.guicedee.activitymaster.forums;

import com.guicedee.client.services.config.IGuiceScanModuleInclusions;
import java.util.Set;

public final class ForumInclusionModule implements IGuiceScanModuleInclusions<ForumInclusionModule> {
    @Override public Set<String> includeModules() { return Set.of("com.guicedee.activitymaster.forums"); }
}
