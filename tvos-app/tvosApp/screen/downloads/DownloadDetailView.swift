// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv
import SwiftUI

struct DownloadDetailRoute: Hashable {
    let downloadId: String
}

struct DownloadDetailView: View {
    let session: Session
    let downloadId: String
    @ObservedObject var model: DownloadsModel
    let onPlay: (String, Bool) -> Void
    let onReturn: () -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.tvAppearance) private var appearance
    @State private var launchedOfflinePlayback = false

    var body: some View {
        Group {
            if let row = model.row(downloadId: downloadId) {
                detail(row)
            } else if model.state.isLoading {
                ProgressView(String(localized: "Loading downloads…"))
            } else {
                Color.clear
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(appearance.background.ignoresSafeArea())
        .navigationTitle("")
        .onAppear {
            launchedOfflinePlayback = false
            dismissIfUnavailable()
        }
        .onChange(of: isAvailable) { _, _ in
            dismissIfUnavailable()
        }
        .onChange(of: model.state.isLoading) { _, _ in
            dismissIfUnavailable()
        }
        .onDisappear {
            if !launchedOfflinePlayback {
                onReturn()
            }
        }
    }

    private func detail(_ row: TvDownloadRow) -> some View {
        ScrollView(.vertical) {
            LazyVStack(alignment: .leading, spacing: TvDimensions.ribbonSpacing) {
                hero(row)
                DownloadActionsView(row: row, model: model) { restart in
                    launchedOfflinePlayback = true
                    onPlay(row.id, restart)
                }
                .padding(.horizontal, TvDimensions.screenInset)

                if let error = model.state.error {
                    Label(error.message, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)

                        .accessibilityElement(children: .combine)
                        .padding(.horizontal, TvDimensions.screenInset)
                }

                metadata(row)
                    .padding(.horizontal, TvDimensions.screenInset)

                if let failure = row.failure {
                    Label(failure.message, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)

                        .accessibilityElement(children: .combine)
                        .padding(.horizontal, TvDimensions.screenInset)
                } else if row.state == .completed && !row.canPlay {
                    Label(
                        String(localized: "The local media is unavailable. Delete this record and download it again."),
                        systemImage: "exclamationmark.triangle"
                    )
                    .foregroundStyle(.orange)

                    .accessibilityElement(children: .combine)
                    .padding(.horizontal, TvDimensions.screenInset)
                }
            }
            .padding(.bottom, TvDimensions.screenBottomInset)
        }
        .background(alignment: .top) {
            DownloadArtworkView(
                session: session,
                row: row,
                role: .backdrop,
                fallbackTitle: nil
            )
            .frame(height: TvDimensions.backdropHeight)
            .overlay(appearance.backdropScrim)
            .overlay(appearance.backdropSideScrim)
            .clipped()
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .ignoresSafeArea(edges: .top)
        }
    }

    private func hero(_ row: TvDownloadRow) -> some View {
        HStack(alignment: .bottom, spacing: 44) {
            DownloadArtworkView(
                session: session,
                row: row,
                role: .poster,
                fallbackTitle: row.title
            )
            .frame(
                width: appearance.cards.detailPosterWidth,
                height: appearance.cards.detailPosterWidth * 1.5
            )
            .clipShape(RoundedRectangle(cornerRadius: TvDimensions.cardCornerRadius))

            VStack(alignment: .leading, spacing: 18) {
                DownloadArtworkView(
                    session: session,
                    row: row,
                    role: .logo,
                    contentMode: .fit,
                    fallbackTitle: row.title
                )
                .accessibilityLabel(row.title)

                headlineMetadata(row)

                if let tagline = row.detail?.tagline, !tagline.isEmpty {
                    Text(tagline)
                        .font(.title3.italic())
                        .foregroundStyle(appearance.secondaryText)
                        .lineLimit(2)
                }
            }
            .frame(maxWidth: 760, alignment: .leading)
        }
        .padding(.horizontal, TvDimensions.screenInset)
        .padding(.top, 54)
    }

    private func headlineMetadata(_ row: TvDownloadRow) -> some View {
        HStack(spacing: 14) {
            if let seriesName = row.seriesName {
                Text(seriesName)
            }
            if let seasonLabel = row.seasonLabel {
                Text(seasonLabel)
            }
            if let episodeLabel = row.episodeLabel {
                Text(episodeLabel)
            }
            if let year = row.detail?.productionYear {
                Text(year.stringValue)
            }
            if let duration = row.durationMs?.int64Value {
                Text(DownloadLabels.duration(milliseconds: duration))
            }
            if let rating = row.detail?.officialRating {
                Text(rating)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 3)
                    .background(.white.opacity(0.14), in: RoundedRectangle(cornerRadius: 5))
            }
            if let community = row.detail?.communityRating?.doubleValue {
                Label(String(format: "%.1f", community), systemImage: "star.fill")
            }
            if let critic = row.detail?.criticRating?.doubleValue {
                Text(String(format: "%.0f%%", critic))
            }
        }
        .font(.callout.weight(.medium))
        .foregroundStyle(appearance.secondaryText)
    }

    @ViewBuilder
    private func metadata(_ row: TvDownloadRow) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            if let overview = row.detail?.overview, !overview.isEmpty {
                ForEach(Array(overviewParagraphs(overview).enumerated()), id: \.offset) { _, paragraph in
                    Text(paragraph)
                        .font(.body)
                        .fixedSize(horizontal: false, vertical: true)

                        .accessibilityElement(children: .combine)
                }
            }

            if let sourceLabel = row.sourceLabel, !sourceLabel.isEmpty {
                metadataFact(String(localized: "Source"), value: sourceLabel)
            }

            if let genres = row.detail?.genres, !genres.isEmpty {
                metadataFact(String(localized: "Genres"), value: genres.joined(separator: ", "))
            }

            if let studios = row.detail?.studios, !studios.isEmpty {
                metadataFact(String(localized: "Studios"), value: studios.joined(separator: ", "))
            }

            if row.detail == nil {
                Text(String(localized: "Saved details unavailable"))
                    .foregroundStyle(.secondary)

                    .accessibilityElement(children: .combine)
            }

            providerIds(row.detail?.externalProviderIds)
            savedTracks(row)
            sourceFacts(row.backend)
            chapters(row.chapters)
            people(row)
        }
        .frame(maxWidth: 980, alignment: .leading)
    }

    @ViewBuilder
    private func providerIds(_ ids: TvDownloadExternalProviderIds?) -> some View {
        if let imdbId = ids?.imdbId {
            metadataFact(String(localized: "IMDb"), value: imdbId)
        }
        if let tmdbId = ids?.tmdbId {
            let type = ids?.tmdbItemType ?? ""
            let value = type.isEmpty ? tmdbId : "\(tmdbId) (\(type))"
            metadataFact(String(localized: "TMDB"), value: value)
        }
    }

    @ViewBuilder
    private func savedTracks(_ row: TvDownloadRow) -> some View {
        if !row.audioTracks.isEmpty {
            metadataFact(
                String(localized: "Audio"),
                value: row.audioTracks.map(trackLabel).joined(separator: ", ")
            )
        }
        if !row.subtitleTracks.isEmpty {
            metadataFact(
                String(localized: "Subtitles"),
                value: row.subtitleTracks.map(trackLabel).joined(separator: ", ")
            )
        }
    }

    @ViewBuilder
    private func sourceFacts(_ backend: TvDownloadBackendInfo?) -> some View {
        if let video = videoDescription(backend) {
            metadataFact(String(localized: "Video"), value: video)
        }
        if let audio = backend?.audioCodec, !audio.isEmpty {
            metadataFact(String(localized: "Audio"), value: audio)
        }
    }

    @ViewBuilder
    private func chapters(_ chapters: [TvChapter]) -> some View {
        if !chapters.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text(String(localized: "Chapters"))
                    .font(.headline)
                ForEach(chapters.indices, id: \.self) { index in
                    let chapter = chapters[index]
                    HStack {
                        Text(chapter.name)
                        Spacer()
                        Text(DownloadLabels.duration(milliseconds: chapter.startMs))
                            .foregroundStyle(.secondary)
                    }
                    .font(.callout)

                    .accessibilityElement(children: .combine)
                }
            }
        }
    }

    @ViewBuilder
    private func people(_ row: TvDownloadRow) -> some View {
        if !row.cast.isEmpty {
            peopleSection(String(localized: "Cast"), people: row.cast)
        }
        if !row.crew.isEmpty {
            peopleSection(String(localized: "Crew"), people: row.crew)
        }
        if !row.otherPeople.isEmpty {
            peopleSection(String(localized: "People"), people: row.otherPeople)
        }
    }

    private func peopleSection(_ title: String, people: [TvDownloadPerson]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.headline)
            ForEach(people.indices, id: \.self) { index in
                let person = people[index]
                metadataFact(person.name, value: person.role ?? "")
            }
        }
    }

    private func metadataFact(_ title: String, value: String) -> some View {
        LabeledContent(title, value: value)

            .accessibilityElement(children: .combine)
    }

    private func overviewParagraphs(_ overview: String) -> [String] {
        let paragraphs =
            overview
                .components(separatedBy: "\n\n")
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
        let source = paragraphs.isEmpty ? [overview] : paragraphs
        return source.flatMap(boundedOverviewSegments)
    }

    private func boundedOverviewSegments(_ paragraph: String) -> [String] {
        var segments = [String]()
        var current = ""
        for word in paragraph.split(whereSeparator: \.isWhitespace) {
            let next = current.isEmpty ? String(word) : "\(current) \(word)"
            if next.count > 480, !current.isEmpty {
                segments.append(current)
                current = String(word)
            } else {
                current = next
            }
        }
        if !current.isEmpty {
            segments.append(current)
        }
        return segments
    }

    private func trackLabel(_ track: TvTrackChoice) -> String {
        let label = track.displayLabel ?? track.languageCode ?? String(track.streamIndex)
        return track.selected ? "\(label) ✓" : label
    }

    private func videoDescription(_ backend: TvDownloadBackendInfo?) -> String? {
        guard let backend else { return nil }
        var parts = [String]()
        if let codec = backend.videoCodec, !codec.isEmpty {
            parts.append(codec)
        }
        if let container = backend.container, !container.isEmpty {
            parts.append(container)
        }
        if let width = backend.videoWidth?.int32Value,
           let height = backend.videoHeight?.int32Value {
            parts.append("\(width)×\(height)")
        }
        if let frameRate = backend.videoFrameRate?.doubleValue {
            parts.append(String(format: String(localized: "%.1f fps"), frameRate))
        }
        if backend.isHdrOrDolbyVision {
            parts.append(String(localized: "HDR"))
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private var isAvailable: Bool {
        model.row(downloadId: downloadId) != nil
    }

    private func dismissIfUnavailable() {
        guard !isAvailable, !model.state.isLoading else { return }
        DispatchQueue.main.async {
            dismiss()
        }
    }
}
