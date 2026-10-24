// SPDX-License-Identifier: MPL-2.0

using System.Text.Json.Serialization;
using MediaBrowser.Model.Plugins;

namespace Jellyfin.Plugin.Launcher.Configuration;

// The host's elevated generic configuration API uses PascalCase. The TV endpoint
// uses separate, explicitly camelCase response records below.
public sealed class PluginConfiguration : BasePluginConfiguration
{
    public PluginConfiguration()
    {
        Revision = LayoutValidation.Revision(this);
    }

    [JsonRequired, JsonPropertyName("SchemaVersion")]
    [JsonNumberHandling(JsonNumberHandling.Strict)]
    public int SchemaVersion { get; set; } = 1;

    [JsonRequired, JsonPropertyName("Revision")]
    public string Revision { get; set; }

    [JsonRequired, JsonPropertyName("PlaybackPreference")]
    public string PlaybackPreference { get; set; } = "jellyscope";

    [JsonRequired, JsonPropertyName("Rows")]
    public LauncherRowConfiguration[] Rows { get; set; } = LayoutValidation.DefaultRows();
}

public sealed class LauncherRowConfiguration
{
    [JsonRequired, JsonPropertyName("Id")]
    public string Id { get; set; } = string.Empty;

    [JsonRequired, JsonPropertyName("Title")]
    public string Title { get; set; } = string.Empty;

    [JsonRequired, JsonPropertyName("Visible")]
    [JsonIgnore(Condition = JsonIgnoreCondition.Never)]
    public bool Visible { get; set; }

    [JsonRequired, JsonPropertyName("Aspect")]
    public string Aspect { get; set; } = string.Empty;

    [JsonRequired, JsonPropertyName("Query")]
    public string Query { get; set; } = string.Empty;

    // Optional for pre-tab schema1 admin JSON and stored XML.
    [JsonPropertyName("Tab")]
    public string? Tab { get; set; }

    [JsonRequired, JsonPropertyName("LibraryId")]
    [JsonIgnore(Condition = JsonIgnoreCondition.Never)]
    public string? LibraryId { get; set; }

    [JsonRequired, JsonPropertyName("Limit")]
    [JsonNumberHandling(JsonNumberHandling.Strict)]
    public int Limit { get; set; }
}

public sealed record LayoutResponse(
    [property: JsonPropertyName("schemaVersion")] int SchemaVersion,
    [property: JsonPropertyName("revision")] string Revision,
    [property: JsonPropertyName("playbackPreference")] string PlaybackPreference,
    [property: JsonPropertyName("rows")] RowResponse[] Rows);

public sealed record RowResponse(
    [property: JsonPropertyName("id")] string Id,
    [property: JsonPropertyName("title")] string Title,
    [property: JsonPropertyName("visible"), JsonIgnore(Condition = JsonIgnoreCondition.Never)] bool Visible,
    [property: JsonPropertyName("aspect")] string Aspect,
    [property: JsonPropertyName("query")] string Query,
    [property: JsonPropertyName("tab")] string Tab,
    [property: JsonPropertyName("libraryId"), JsonIgnore(Condition = JsonIgnoreCondition.Never)] string? LibraryId,
    [property: JsonPropertyName("limit")] int Limit);
