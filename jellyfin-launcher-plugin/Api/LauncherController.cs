// SPDX-License-Identifier: MPL-2.0

using System.Text.Json.Serialization;
using Jellyfin.Plugin.Launcher.Configuration;
using MediaBrowser.Common.Api;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Net;
using MediaBrowser.Model.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Launcher.Api;

[ApiController]
[Route("Launcher")]
public sealed class LauncherController(IAuthorizationContext authorization, IUserViewManager views) : ControllerBase
{
    [HttpGet("Configuration")]
    [Authorize]
    public async Task<ActionResult<LayoutResponse>> GetConfiguration()
    {
        Response.Headers.CacheControl = "no-store";
        var auth = await authorization.GetAuthorizationInfo(HttpContext).ConfigureAwait(false);
        if (!auth.IsAuthenticated || auth.IsApiKey || auth.User is null || auth.UserId == Guid.Empty)
        {
            return Unauthorized();
        }

        var plugin = Plugin.Instance;
        if (plugin is null)
        {
            return StatusCode(StatusCodes.Status503ServiceUnavailable);
        }

        var allowed = views.GetUserViews(new UserViewQuery
        {
            User = auth.User,
            IncludeHidden = false,
            IncludeExternalContent = false
        }).Select(view => view.Id).ToHashSet();
        var (configuration, _) = plugin.ReadLayout();
        configuration.Rows = configuration.Rows.Where(row =>
            row.LibraryId is null || Guid.TryParseExact(row.LibraryId, "N", out var id) && allowed.Contains(id)).ToArray();
        // Hash the projected content, not the full administrative configuration.
        return Ok(LayoutValidation.Response(LayoutValidation.Canonicalize(configuration)));
    }

    [HttpGet("Libraries")]
    [Authorize(Policy = Policies.RequiresElevation)]
    public async Task<ActionResult<LibrariesResponse>> GetLibraries()
    {
        Response.Headers.CacheControl = "no-store";
        var auth = await authorization.GetAuthorizationInfo(HttpContext).ConfigureAwait(false);
        if (!auth.IsAuthenticated || auth.IsApiKey || auth.User is null || auth.UserId == Guid.Empty)
        {
            return Unauthorized();
        }

        var plugin = Plugin.Instance;
        if (plugin is null)
        {
            return StatusCode(StatusCodes.Status503ServiceUnavailable);
        }

        var libraries = views.GetUserViews(new UserViewQuery
        {
            User = auth.User,
            IncludeHidden = false,
            IncludeExternalContent = false
        }).Select(view => new LibraryResponse(view.Id.ToString("N"), view.Name)).ToArray();
        var (configuration, invalid) = plugin.ReadLayout();
        return Ok(new LibrariesResponse(libraries, invalid ? "invalid" : "current", LayoutValidation.Response(configuration)));
    }
}

public sealed record LibraryResponse(
    [property: JsonPropertyName("id")] string Id,
    [property: JsonPropertyName("name")] string Name);

public sealed record LibrariesResponse(
    [property: JsonPropertyName("libraries")] LibraryResponse[] Libraries,
    [property: JsonPropertyName("configurationStatus")] string ConfigurationStatus,
    [property: JsonPropertyName("configuration")] LayoutResponse Configuration);
