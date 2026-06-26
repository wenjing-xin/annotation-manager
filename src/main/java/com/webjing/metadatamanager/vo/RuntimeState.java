package com.webjing.metadatamanager.vo;

import java.util.Set;

public record RuntimeState(String activeThemeName, Set<String> startedPluginNames) {
}
