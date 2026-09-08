// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

@MainActor
private final class DownloadRequestModel: ObservableObject {
    @Published private(set) var state: TvDownloadRequestState
    private let presenter: TvDownloadRequestPresenter
    private var handle: WatchHandle?
    private var closed = false

    init(session: Session, selection: TvDetailPlaybackSelection) {
        let presenter = TvosEntry.shared.downloadRequestPresenter(session: session, selection: selection)
        self.presenter = presenter
        self.state = presenter.state.value as! TvDownloadRequestState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
    }

    func selectQuality(_ id: String) {
        presenter.selectQuality(qualityId: id)
    }

    func setFixedSubtitleOff(_ off: Bool) {
        presenter.setFixedSubtitleOff(off: off)
    }

    func preview() {
        presenter.preview()
    }

    func confirmOriginalWithoutSubtitle() {
        presenter.confirmOriginalWithoutSubtitle()
    }

    func confirmFixedBurnIn() {
        presenter.confirmFixedBurnIn()
    }

    func dismissConfirmation() {
        presenter.dismissConfirmation()
    }

    func enqueue() {
        presenter.enqueue()
    }

    func retryLoad() {
        presenter.retryLoad()
    }

    func close() {
        guard !closed else { return }
        closed = true
        handle?.close()
        handle = nil
        presenter.close()
    }

    deinit {
        if !closed {
            handle?.close()
            presenter.close()
        }
    }
}

struct DownloadRequestView: View {
    @StateObject private var model: DownloadRequestModel
    @Environment(\.dismiss) private var dismiss

    init(session: Session, selection: TvDetailPlaybackSelection) {
        _model = StateObject(wrappedValue: DownloadRequestModel(session: session, selection: selection))
    }

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(String(localized: "Download"))
        }
        .confirmationDialog(
            confirmationTitle,
            isPresented: confirmationBinding,
            titleVisibility: .visible
        ) {
            if model.state.stage == .confirmoriginalwithoutsubtitle {
                Button(String(localized: "Download Without This Subtitle")) {
                    model.confirmOriginalWithoutSubtitle()
                }
            } else if model.state.stage == .confirmfixedburnin {
                Button(String(localized: "Burn Subtitle Into Video")) {
                    model.confirmFixedBurnIn()
                }
            }
            Button(String(localized: "Cancel"), role: .cancel) {
                model.dismissConfirmation()
            }
        } message: {
            Text(confirmationMessage)
        }
        .onDisappear(perform: model.close)
    }

    @ViewBuilder
    private var content: some View {
        switch model.state.stage {
        case .loading:
            ProgressView(String(localized: "Loading download options…"))
        case .finished:
            VStack(spacing: 26) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 58))
                    .foregroundStyle(.green)
                Text(model.state.outcome?.message ?? String(localized: "The download request was saved."))
                    .multilineTextAlignment(.center)
                Button(String(localized: "Done"), action: dismiss.callAsFunction)
                    .buttonStyle(.borderedProminent)
            }
            .padding(70)
        case .failed:
            ContentUnavailableView {
                Label(String(localized: "Download unavailable"), systemImage: "exclamationmark.triangle")
            } description: {
                Text(model.state.error?.message ?? String(localized: "The download request could not be completed."))
            } actions: {
                Button(String(localized: "Try Again"), action: model.preview)
                Button(String(localized: "Reload Source"), action: model.retryLoad)
            }
        default:
            requestForm
        }
    }

    private var requestForm: some View {
        Form {
            if let summary = model.state.summary {
                Section(String(localized: "Selection")) {
                    LabeledContent(String(localized: "Title"), value: summary.title)
                    LabeledContent(String(localized: "Version"), value: summary.sourceLabel)
                    if let audio = summary.audioLabel {
                        LabeledContent(String(localized: "Audio"), value: audio)
                    }
                    LabeledContent(
                        String(localized: "Subtitles"),
                        value: summary.subtitleLabel ?? String(localized: "Off")
                    )
                }
            }

            Section(String(localized: "Quality")) {
                Picker(String(localized: "Download Quality"), selection: qualityBinding) {
                    ForEach(model.state.qualityChoices, id: \.id) { choice in
                        Text(DownloadLabels.quality(choice)).tag(choice.id)
                    }
                }

                if selectedQuality?.kind == .fixed && model.state.fixedSubtitleRequirement != .none {
                    Picker(String(localized: "Fixed Quality Subtitles"), selection: fixedSubtitleBinding) {
                        Text(String(localized: "Use Current Selection")).tag(false)
                        Text(String(localized: "Off")).tag(true)
                    }
                    if model.state.fixedSubtitleRequirement == .offrequired && !model.state.fixedSubtitleOff {
                        Text(String(localized: "This subtitle cannot be stored with Fixed quality. Choose Off or Original."))
                            .foregroundStyle(.orange)
                    } else if model.state.fixedSubtitleRequirement == .burninconfirmation && !model.state.fixedSubtitleOff {
                        Text(String(localized: "The selected subtitle must be permanently burned into Fixed quality video."))
                            .foregroundStyle(.secondary)
                    }
                }
            }

            if let estimatedBytes = model.state.estimatedBytes?.int64Value {
                Section(String(localized: "Review")) {
                    LabeledContent(String(localized: "Estimated size"), value: DownloadLabels.bytes(estimatedBytes))
                    Button(String(localized: "Start Download"), action: model.enqueue)
                        .buttonStyle(.borderedProminent)
                        .disabled(model.state.stage == .submitting)
                }
            } else {
                Section {
                    Button(String(localized: "Review Download Size"), action: model.preview)
                        .disabled(model.state.stage == .previewing || model.state.stage == .submitting)
                }
            }

            if model.state.stage == .previewing || model.state.stage == .submitting {
                Section {
                    ProgressView(
                        model.state.stage == .submitting
                            ? String(localized: "Starting download…")
                            : String(localized: "Checking download…")
                    )
                }
            }

            if let error = model.state.error {
                Section {
                    Label(error.message, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)
                }
            }

            Section {
                Text(String(localized: "Downloads transfer only while JellyScope is active. Apple TV may later reclaim cached media, and unavailable copies may need downloading again."))
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var selectedQuality: TvDownloadQualityChoice? {
        model.state.qualityChoices.first { $0.id == model.state.selectedQualityId }
    }

    private var qualityBinding: Binding<String> {
        Binding(
            get: { model.state.selectedQualityId },
            set: model.selectQuality
        )
    }

    private var fixedSubtitleBinding: Binding<Bool> {
        Binding(
            get: { model.state.fixedSubtitleOff },
            set: model.setFixedSubtitleOff
        )
    }

    private var confirmationBinding: Binding<Bool> {
        Binding(
            get: {
                model.state.stage == .confirmoriginalwithoutsubtitle ||
                    model.state.stage == .confirmfixedburnin
            },
            set: { showing in
                if !showing {
                    model.dismissConfirmation()
                }
            }
        )
    }

    private var confirmationTitle: String {
        model.state.stage == .confirmfixedburnin
            ? String(localized: "Burn this subtitle into the video?")
            : String(localized: "Download without this subtitle?")
    }

    private var confirmationMessage: String {
        let label = model.state.warningSubtitleLabel ?? String(localized: "Selected subtitle")
        return model.state.stage == .confirmfixedburnin
            ? String(format: String(localized: "%@ will be permanently visible and cannot be turned off during playback."), label)
            : String(format: String(localized: "%@ is bitmap based and cannot be stored as an external sidecar. The video can be downloaded without it."), label)
    }
}
