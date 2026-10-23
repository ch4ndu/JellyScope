// SPDX-License-Identifier: MPL-2.0

using Jellyfin.Plugin.Launcher.Configuration;
using MediaBrowser.Common.Configuration;
using MediaBrowser.Common.Plugins;
using MediaBrowser.Model.Plugins;
using MediaBrowser.Model.Serialization;

namespace Jellyfin.Plugin.Launcher;

public sealed class Plugin : BasePlugin<PluginConfiguration>, IHasWebPages
{
    private readonly object _layoutLock = new();

    public Plugin(IApplicationPaths applicationPaths, IXmlSerializer xmlSerializer)
        : base(applicationPaths, xmlSerializer)
    {
        Instance = this;
    }

    public static Plugin? Instance { get; private set; }

    public override string Name => "JellyScope Launcher";

    public override Guid Id => new("29172c40-7d54-4d15-9673-a772a14d5f3c");

    public IEnumerable<PluginPageInfo> GetPages() =>
    [
        new PluginPageInfo
        {
            Name = "jellyscope-launcher",
            DisplayName = Name,
            EmbeddedResourcePath = "Jellyfin.Plugin.Launcher.Configuration.configPage.html"
        }
    ];

    public override void UpdateConfiguration(BasePluginConfiguration configuration)
    {
        if (configuration is not PluginConfiguration layout)
        {
            throw new ArgumentException("Invalid launcher configuration type.");
        }

        lock (_layoutLock)
        {
            var copy = LayoutValidation.Canonicalize(layout);
            var previous = Configuration;
            try
            {
                base.UpdateConfiguration(copy);
            }
            catch
            {
                Configuration = previous;
                throw;
            }
        }
    }

    // These snapshots are detached. The host's nonvirtual generic GET still
    // returns its base object; updates replace that reference rather than mutate it.
    internal (PluginConfiguration Configuration, bool Invalid) ReadLayout()
    {
        lock (_layoutLock)
        {
            try
            {
                return (LayoutValidation.Canonicalize(Configuration), false);
            }
            catch (ArgumentException)
            {
                return (LayoutValidation.Defaults(), true);
            }
        }
    }
}
