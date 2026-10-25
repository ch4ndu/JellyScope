// SPDX-License-Identifier: MPL-2.0

using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Jellyfin.Plugin.Launcher.Configuration;

internal static class LayoutValidation
{
    public static LauncherRowConfiguration[] DefaultRows() =>
    [
        DefaultRow("continue-watching", "Play Next", "resume"),
        DefaultRow("next-up", "Up Next", "nextUp"),
        DefaultRow("recently-added", "Recently Added", "latest"),
        DefaultRow("favorites", "Favorites", "favorites")
    ];

    public static PluginConfiguration Defaults() => Canonicalize(new PluginConfiguration());

    public static PluginConfiguration Canonicalize(PluginConfiguration configuration)
    {
        if (configuration is null || configuration.SchemaVersion is not (1 or 2) || configuration.PlaybackPreference != "jellyscope" ||
            configuration.Revision is null || !Regex.IsMatch(configuration.Revision, "\\A[a-fA-F0-9]{64}\\z") ||
            configuration.Rows is null || configuration.Rows.Length > 12)
        {
            throw new ArgumentException("Unsupported or invalid launcher layout.");
        }

        var ids = new HashSet<string>(StringComparer.Ordinal);
        var rows = new List<LauncherRowConfiguration>(configuration.Rows.Length);
        foreach (var row in configuration.Rows)
        {
            if (row is null || row.Id is null || !Regex.IsMatch(row.Id, "\\A[a-z0-9][a-z0-9_-]{0,63}\\z") || !ids.Add(row.Id) ||
                row.Title is null || row.Title.Length is < 1 or > 80 || row.Title.Any(c => char.IsControl(c) || c is '\u2028' or '\u2029') ||
                row.Aspect is not ("landscape" or "poster" or "square") ||
                row.Query is not ("resume" or "nextUp" or "latest" or "favorites" or "library" or "latestMovies" or "latestEpisodes" or "latestSeries") ||
                (configuration.SchemaVersion == 1 && row.Query is not ("resume" or "nextUp" or "latest" or "favorites" or "library")) ||
                row.Tab is not (null or "essentials" or "jellyfin") ||
                row.Limit is < 1 or > 20 ||
                (row.LibraryId is not null && !ValidLibraryId(row.LibraryId)) ||
                (row.Query == "library" && row.LibraryId is null))
            {
                throw new ArgumentException("Invalid launcher row. Check its ID, title, query, tab, library, aspect and limit.");
            }

            rows.Add(new LauncherRowConfiguration
            {
                Id = row.Id,
                Title = row.Title,
                Visible = row.Visible,
                Aspect = row.Aspect,
                Query = row.Query,
                Tab = row.Tab ?? DefaultTab(row.Query),
                LibraryId = row.LibraryId is null ? null : Guid.ParseExact(row.LibraryId, "N").ToString("N"),
                Limit = row.Limit
            });
        }

        var copy = new PluginConfiguration
        {
            SchemaVersion = 2,
            PlaybackPreference = "jellyscope",
            Rows = rows.ToArray()
        };
        copy.Revision = Revision(copy);
        return copy;
    }

    public static LayoutResponse Response(PluginConfiguration configuration) => new(
        configuration.SchemaVersion,
        configuration.Revision,
        configuration.PlaybackPreference,
        configuration.Rows.Select(row => new RowResponse(
            row.Id, row.Title, row.Visible, row.Aspect, row.Query, row.Tab ?? DefaultTab(row.Query), row.LibraryId, row.Limit)).ToArray());

    private static string DefaultTab(string query) => query is "resume" or "nextUp" or "favorites" ? "essentials" : "jellyfin";

    private static bool ValidLibraryId(string value) =>
        Regex.IsMatch(value, "\\A[a-f0-9]{32}\\z") && Guid.TryParseExact(value, "N", out _);

    private static LauncherRowConfiguration DefaultRow(string id, string title, string query) => new()
    {
        Id = id,
        Title = title,
        Visible = true,
        Aspect = "landscape",
        Query = query,
        Tab = DefaultTab(query),
        LibraryId = null,
        Limit = 20
    };

    internal static string Revision(PluginConfiguration configuration)
    {
        using var bytes = new MemoryStream();
        using (var writer = new Utf8JsonWriter(bytes))
        {
            writer.WriteStartObject();
            writer.WriteNumber("schemaVersion", configuration.SchemaVersion);
            writer.WriteString("playbackPreference", configuration.PlaybackPreference);
            writer.WriteStartArray("rows");
            foreach (var row in configuration.Rows)
            {
                writer.WriteStartObject();
                writer.WriteString("id", row.Id);
                writer.WriteString("title", row.Title);
                writer.WriteBoolean("visible", row.Visible);
                writer.WriteString("aspect", row.Aspect);
                writer.WriteString("query", row.Query);
                writer.WriteString("tab", row.Tab ?? DefaultTab(row.Query));
                writer.WriteString("libraryId", row.LibraryId);
                writer.WriteNumber("limit", row.Limit);
                writer.WriteEndObject();
            }

            writer.WriteEndArray();
            writer.WriteEndObject();
        }

        return Convert.ToHexString(SHA256.HashData(bytes.ToArray())).ToLowerInvariant();
    }
}
