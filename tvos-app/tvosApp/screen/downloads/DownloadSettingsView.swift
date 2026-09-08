// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct DownloadSettingsView: View {
    @StateObject private var model: DownloadsModel
    @State private var customWholeGb = ""

    init(session: Session) {
        _model = StateObject(wrappedValue: DownloadsModel(session: session))
    }

    var body: some View {
        NavigationStack {
            Form {
                if let storage = model.state.storage {
                    Section(String(localized: "Storage Usage")) {
                        LabeledContent(String(localized: "All downloads"), value: DownloadLabels.bytes(storage.physicalBytes))
                        LabeledContent(String(localized: "This account"), value: DownloadLabels.bytes(storage.currentAccountPhysicalBytes))
                        LabeledContent(String(localized: "Other accounts"), value: DownloadLabels.bytes(storage.otherAccountsPhysicalBytes))
                        LabeledContent(String(localized: "Reserved for queued downloads"), value: DownloadLabels.bytes(storage.outstandingReservationBytes))
                        LabeledContent(String(localized: "Safe space available"), value: DownloadLabels.bytes(max(0, storage.deviceAvailableBytes - storage.safetyReserveBytes)))
                        if storage.overAllocation {
                            Label(String(localized: "Current downloads exceed the configured allocation."), systemImage: "exclamationmark.triangle")
                                .foregroundStyle(.orange)
                        }
                    }

                    Section(String(localized: "Download Storage Allocation")) {
                        if let quotaBytes = storage.quotaBytes?.int64Value {
                            LabeledContent(String(localized: "Current allocation"), value: DownloadLabels.bytes(quotaBytes))
                        } else {
                            Text(String(localized: "No allocation configured"))
                                .foregroundStyle(.secondary)
                        }

                        ForEach(presets(maximumBytes: storage.maximumConfigurableQuotaBytes), id: \.self) { wholeGb in
                            Button(String(format: String(localized: "%lld GB"), wholeGb)) {
                                model.configureQuota(wholeGb: wholeGb)
                            }
                            .disabled(model.state.isRefreshingStorage)
                        }

                        TextField(String(localized: "Custom allocation in whole GB"), text: $customWholeGb)
                        Button(String(localized: "Apply Custom Allocation")) {
                            guard let value = Int64(customWholeGb), value > 0 else { return }
                            model.configureQuota(wholeGb: value)
                        }
                        .disabled(Int64(customWholeGb) == nil || model.state.isRefreshingStorage)
                    }
                } else {
                    Section {
                        ProgressView(String(localized: "Loading storage…"))
                    }
                }

                if let error = model.state.error {
                    Section {
                        Label(error.message, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.orange)
                    }
                }

                Section(String(localized: "About Download Storage")) {
                    Text(String(localized: "Apple TV manages this cache and may reclaim media independently from its download record. Unavailable copies may need downloading again."))
                    Text(String(localized: "Downloads transfer only while JellyScope is active. Changing the allocation never deletes existing downloads automatically."))
                }
                .foregroundStyle(.secondary)
            }
            .navigationTitle(String(localized: "Download Storage"))
        }
        .onDisappear(perform: model.close)
    }

    private func presets(maximumBytes: Int64) -> [Int64] {
        let maximumGb = maximumBytes / 1_000_000_000
        let values: [Int64] = [1, 5, 10, 20, 50, 100]
        let available = values.filter { $0 <= maximumGb }
        return available.isEmpty && maximumGb > 0 ? [maximumGb] : available
    }
}
