package com.bxv.sysmindagent.agent;

import com.bxv.sysmindagent.agent.model.ToolCall;
import com.bxv.sysmindagent.agent.model.ToolResult;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class ToolResultAnswerFormatter {

    private static final int MAX_NEWS_ARTICLES = 8;
    private static final int MAX_GENERIC_LIST_ITEMS = 12;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.US)
            .withZone(ZoneId.systemDefault());

    private final ObjectMapper objectMapper;
    private final NumberFormat numberFormat;

    ToolResultAnswerFormatter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.numberFormat = NumberFormat.getNumberInstance(Locale.US);
        this.numberFormat.setMaximumFractionDigits(2);
    }

    String format(ToolCall toolCall, ToolResult toolResult) {
        JsonNode content = normalize(toolResult.content());
        String toolName = toolResult.toolName() == null ? toolCall.toolName() : toolResult.toolName();

        if (content == null || content.isNull() || content.isMissingNode()) {
            return "**%s**%n%nNo details were returned.".formatted(formatToolTitle(toolName));
        }
        if (content.isString()) {
            return "**%s**%n%n%s".formatted(formatToolTitle(toolName), content.stringValue());
        }
        if ("machine_status".equals(toolName) && content.isObject()) {
            return formatMachineStatus(content);
        }
        if ("chroma_status".equals(toolName) && content.isObject()) {
            return formatChromaStatus(content);
        }
        if ("latest_news".equals(toolName) && content.isObject()) {
            return formatNewsResult(content);
        }
        if (content.isObject()) {
            return formatGenericRecord(formatToolTitle(toolName), content);
        }
        if (content.isArray()) {
            return formatGenericList(formatToolTitle(toolName), content);
        }
        return "**%s**%n%n%s".formatted(formatToolTitle(toolName), formatGenericValue(content));
    }

    private JsonNode normalize(JsonNode value) {
        if (value == null) {
            return null;
        }
        if (value.isString()) {
            return parseJson(value.stringValue());
        }
        if (value.isArray()) {
            List<String> textItems = new ArrayList<>();
            for (JsonNode item : value) {
                JsonNode text = item == null ? null : item.get("text");
                if (text == null || !text.isString() || text.stringValue().isBlank()) {
                    return value;
                }
                textItems.add(text.stringValue());
            }
            if (!textItems.isEmpty()) {
                String combined = String.join("\n\n", textItems);
                JsonNode parsed = parseJson(combined);
                return parsed == null ? objectMapper.getNodeFactory().stringNode(combined) : parsed;
            }
        }
        if (value.isObject()) {
            JsonNode structuredContent = value.get("structuredContent");
            if (structuredContent != null) {
                return normalize(structuredContent);
            }
            JsonNode result = value.get("result");
            if (result != null) {
                return normalize(result);
            }
            JsonNode content = value.get("content");
            if (content != null && content.isArray()) {
                return normalize(content);
            }
        }
        return value;
    }

    private JsonNode parseJson(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank() || (!trimmed.startsWith("{") && !trimmed.startsWith("["))) {
            return null;
        }
        try {
            return objectMapper.readTree(trimmed);
        } catch (JacksonException exception) {
            return objectMapper.getNodeFactory().stringNode(value);
        }
    }

    private String formatMachineStatus(JsonNode status) {
        JsonNode cpu = objectAt(status, "processorDetails");
        JsonNode memory = objectAt(status, "memoryDetails");
        JsonNode storage = objectAt(status, "storageDetails");
        JsonNode runtime = objectAt(status, "systemStatus");
        JsonNode system = objectAt(status, "systemDetails");
        JsonNode power = objectAt(status, "powerDetails");

        return joinLines(List.of(
                "**Machine status**",
                bullet("Computer", textAt(status, "computerName")),
                bullet("OS", textAt(status, "operatingSystem")),
                bullet("Machine type", textAt(status, "machineType")),
                bullet("Processor", textAt(status, "processor")),
                bullet("CPU", cpuSummary(cpu)),
                bullet("CPU load", loadSummary(cpu)),
                bullet("RAM", memorySummary(memory)),
                bullet("Swap", swapSummary(memory)),
                bullet("Storage", storageSummary(storage)),
                bullet("Uptime", textAt(runtime, "runningFor")),
                bullet("Boot time", textAt(runtime, "lastStarted")),
                bullet("Power", powerSummary(power)),
                bullet("User", textAt(system, "loggedInUser")),
                bullet("Generated", textAt(status, "generatedAt"))
        ));
    }

    private String formatChromaStatus(JsonNode status) {
        Boolean healthy = booleanAt(status, "healthy");
        return joinLines(List.of(
                Boolean.TRUE.equals(healthy)
                        ? "**Chroma is healthy**"
                        : Boolean.FALSE.equals(healthy) ? "**Chroma needs attention**" : "**Chroma status**",
                bullet("URL", textAt(status, "url")),
                bullet("Tenant", textAt(status, "tenant")),
                bullet("Database", textAt(status, "database")),
                bullet("Collection", textAt(status, "collection")),
                bullet("Version", textAt(status, "version")),
                bullet("Health check", textAt(status, "healthcheck")),
                bullet("Error", textAt(status, "error"))
        ));
    }

    private String formatNewsResult(JsonNode result) {
        List<String> lines = new ArrayList<>();
        lines.add("**Latest news**");
        String fetchedAt = formatDateTime(textAt(result, "fetchedAt"));
        if (fetchedAt != null) {
            lines.add("> Updated " + fetchedAt);
        }
        String error = textAt(result, "error");
        if (error != null) {
            lines.add(bullet("Error", error));
        }

        JsonNode articles = result.get("articles");
        if (articles != null && articles.isArray()) {
            int count = 0;
            for (JsonNode article : articles) {
                if (count >= MAX_NEWS_ARTICLES || article == null || !article.isObject()) {
                    continue;
                }
                String title = textAt(article, "title");
                if (title == null) {
                    continue;
                }
                String source = textAt(article, "source");
                String publishedAt = formatDateTime(textAt(article, "publishedAt"));
                String url = textAt(article, "url");
                String details = joinText(List.of(source, publishedAt), " - ");
                lines.add(newsBullet(title, url, details));
                count += 1;
            }
        }

        return String.join("\n", lines);
    }

    private String formatGenericRecord(String title, JsonNode record) {
        List<String> lines = new ArrayList<>();
        lines.add("**" + title + "**");
        for (Map.Entry<String, JsonNode> entry : record.properties()) {
            String value = formatGenericValue(normalize(entry.getValue()));
            if (value != null) {
                lines.add(bullet(labelFromKey(entry.getKey()), value));
            }
        }
        if (lines.size() == 1) {
            lines.add("");
            lines.add("No displayable details were returned.");
        }
        return String.join("\n", lines);
    }

    private String formatGenericList(String title, JsonNode values) {
        List<String> lines = new ArrayList<>();
        lines.add("**" + title + "**");
        int count = 0;
        for (JsonNode value : values) {
            if (count >= MAX_GENERIC_LIST_ITEMS) {
                break;
            }
            String formatted = formatGenericValue(normalize(value));
            if (formatted != null) {
                lines.add("- " + formatted);
                count += 1;
            }
        }
        if (lines.size() == 1) {
            lines.add("");
            lines.add("No displayable details were returned.");
        }
        return String.join("\n", lines);
    }

    private String formatGenericValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean() ? "Yes" : "No";
        }
        if (value.isNumber()) {
            return formatNumber(value.asDouble());
        }
        if (value.isString()) {
            return value.stringValue().isBlank() ? null : value.stringValue();
        }
        if (value.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : value) {
                String formatted = formatGenericValue(normalize(item));
                if (formatted != null) {
                    values.add(formatted);
                }
            }
            return values.isEmpty() ? null : String.join(", ", values);
        }
        if (value.isObject()) {
            List<String> values = new ArrayList<>();
            for (Map.Entry<String, JsonNode> entry : value.properties()) {
                String formatted = formatGenericValue(normalize(entry.getValue()));
                if (formatted != null) {
                    values.add(labelFromKey(entry.getKey()) + ": " + formatted);
                }
            }
            return values.isEmpty() ? null : String.join("; ", values);
        }
        return value.toString();
    }

    private JsonNode objectAt(JsonNode record, String key) {
        JsonNode value = record == null ? null : record.get(key);
        return value != null && value.isObject() ? value : null;
    }

    private String textAt(JsonNode record, String key) {
        JsonNode value = record == null ? null : record.get(key);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        String text = value.isString() ? value.stringValue() : null;
        return text == null || text.isBlank() ? null : text.trim();
    }

    private Double numberAt(JsonNode record, String key) {
        JsonNode value = record == null ? null : record.get(key);
        return value != null && value.isNumber() ? value.asDouble() : null;
    }

    private Boolean booleanAt(JsonNode record, String key) {
        JsonNode value = record == null ? null : record.get(key);
        return value != null && value.isBoolean() ? value.asBoolean() : null;
    }

    private String cpuSummary(JsonNode cpu) {
        if (cpu == null) {
            return null;
        }
        String coreSummary = textAt(cpu, "coreSummary");
        if (coreSummary != null) {
            return coreSummary;
        }
        Double physicalCores = numberAt(cpu, "physicalCpuCores");
        Double logicalCores = numberAt(cpu, "logicalCpuCores");
        Double totalCores = numberAt(cpu, "totalCpuCores");
        if (physicalCores != null && logicalCores != null) {
            return "%s physical / %s logical cores".formatted(formatNumber(physicalCores), formatNumber(logicalCores));
        }
        return totalCores == null ? null : formatNumber(totalCores) + " cores";
    }

    private String loadSummary(JsonNode cpu) {
        if (cpu == null) {
            return null;
        }
        Double usage = numberAt(cpu, "currentCpuUsagePercent");
        List<String> loadAverages = new ArrayList<>();
        addNumber(loadAverages, numberAt(cpu, "loadAverageOneMinute"));
        addNumber(loadAverages, numberAt(cpu, "loadAverageFiveMinutes"));
        addNumber(loadAverages, numberAt(cpu, "loadAverageFifteenMinutes"));

        if (usage != null && !loadAverages.isEmpty()) {
            return formatPercent(usage) + " used; load averages " + String.join(", ", loadAverages);
        }
        if (usage != null) {
            return formatPercent(usage) + " used";
        }
        return loadAverages.isEmpty() ? null : "Load averages " + String.join(", ", loadAverages);
    }

    private String memorySummary(JsonNode memory) {
        if (memory == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        Double used = numberAt(memory, "usedGb");
        Double total = numberAt(memory, "totalGb");
        Double available = numberAt(memory, "availableGb");
        Double usage = numberAt(memory, "usagePercent");
        if (used != null && total != null) {
            parts.add(formatNumber(used) + " GB used of " + formatNumber(total) + " GB");
        } else if (total != null) {
            parts.add(formatNumber(total) + " GB total");
        }
        if (available != null) {
            parts.add(formatNumber(available) + " GB available");
        }
        if (usage != null) {
            parts.add(formatPercent(usage) + " used");
        }
        addText(parts, textAt(memory, "status"));
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    private String swapSummary(JsonNode memory) {
        if (memory == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        Double used = numberAt(memory, "swapPageFileUsedGb");
        Double total = numberAt(memory, "swapPageFileTotalGb");
        Double usage = numberAt(memory, "swapPageFileUsagePercent");
        if (used != null && total != null) {
            parts.add(formatNumber(used) + " GB used of " + formatNumber(total) + " GB");
        }
        if (usage != null) {
            parts.add(formatPercent(usage) + " used");
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    private String storageSummary(JsonNode storage) {
        if (storage == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        Double used = numberAt(storage, "usedGb");
        Double total = numberAt(storage, "totalGb");
        Double free = numberAt(storage, "freeGb");
        Double usage = numberAt(storage, "usagePercent");
        if (used != null && total != null) {
            parts.add(formatNumber(used) + " GB used of " + formatNumber(total) + " GB");
        } else if (total != null) {
            parts.add(formatNumber(total) + " GB total");
        }
        if (free != null) {
            parts.add(formatNumber(free) + " GB free");
        }
        if (usage != null) {
            parts.add(formatPercent(usage) + " used");
        }
        addText(parts, textAt(storage, "status"));
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    private String powerSummary(JsonNode power) {
        if (power == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        Double battery = numberAt(power, "batteryPercent");
        if (battery != null) {
            parts.add(formatPercent(battery) + " battery");
        }
        addText(parts, textAt(power, "powerSource"));
        Boolean charging = booleanAt(power, "charging");
        if (charging != null) {
            parts.add(charging ? "charging" : "not charging");
        }
        addText(parts, firstText(power, "health", "condition", "status"));
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    private String firstText(JsonNode record, String... keys) {
        for (String key : keys) {
            String text = textAt(record, key);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private String bullet(String label, String value) {
        return value == null ? "" : "- **" + label + ":** " + value;
    }

    private String newsBullet(String title, String url, String details) {
        String headline = url == null ? "**" + escapeMarkdown(title) + "**" : "[" + escapeMarkdown(title) + "](" + url + ")";
        return "- " + headline + (details == null || details.isBlank() ? "" : " (" + details + ")");
    }

    private String formatToolTitle(String toolName) {
        if ("machine_status".equals(toolName)) {
            return "Machine status";
        }
        if ("chroma_status".equals(toolName)) {
            return "Chroma status";
        }
        if ("latest_news".equals(toolName)) {
            return "Latest news";
        }
        return labelFromKey(toolName == null ? "tool" : toolName);
    }

    private String labelFromKey(String key) {
        String spaced = key
                .replaceAll("[_-]+", " ")
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .trim();
        return spaced.isBlank() ? key : spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
    }

    private String joinLines(List<String> lines) {
        return lines.stream().filter(line -> line != null && !line.isBlank()).reduce((left, right) -> left + "\n" + right).orElse("");
    }

    private String joinText(List<String> values, String delimiter) {
        List<String> present = values.stream().filter(value -> value != null && !value.isBlank()).toList();
        return present.isEmpty() ? null : String.join(delimiter, present);
    }

    private void addNumber(List<String> values, Double value) {
        if (value != null) {
            values.add(formatNumber(value));
        }
    }

    private void addText(List<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    private String formatNumber(double value) {
        return numberFormat.format(value);
    }

    private String formatPercent(double value) {
        return formatNumber(value) + "%";
    }

    private String formatDateTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return DATE_TIME_FORMATTER.format(Instant.parse(value));
        } catch (Exception exception) {
            return value;
        }
    }

    private String escapeMarkdown(String value) {
        return value.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]");
    }
}
