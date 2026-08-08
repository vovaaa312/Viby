package com.example.viby.update;

import androidx.annotation.Nullable;

/** Numeric comparison for release tags such as v1.2.3 and app versions such as 1.2.3. */
public final class ReleaseVersion {

    private ReleaseVersion() {
    }

    public static boolean isNewer(@Nullable String candidate, @Nullable String current) {
        int[] candidateParts = parse(candidate);
        int[] currentParts = parse(current);
        if (candidateParts == null || currentParts == null) {
            return false;
        }
        int count = Math.max(candidateParts.length, currentParts.length);
        for (int i = 0; i < count; i++) {
            int candidatePart = i < candidateParts.length ? candidateParts[i] : 0;
            int currentPart = i < currentParts.length ? currentParts[i] : 0;
            if (candidatePart != currentPart) {
                return candidatePart > currentPart;
            }
        }
        return false;
    }

    @Nullable
    private static int[] parse(@Nullable String version) {
        if (version == null) {
            return null;
        }
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        int suffix = normalized.indexOf('-');
        if (suffix >= 0) {
            normalized = normalized.substring(0, suffix);
        }
        suffix = normalized.indexOf('+');
        if (suffix >= 0) {
            normalized = normalized.substring(0, suffix);
        }
        if (normalized.isEmpty()) {
            return null;
        }

        String[] rawParts = normalized.split("\\.");
        int[] parts = new int[rawParts.length];
        for (int i = 0; i < rawParts.length; i++) {
            if (rawParts[i].isEmpty()) {
                return null;
            }
            try {
                parts[i] = Integer.parseInt(rawParts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return parts;
    }
}
