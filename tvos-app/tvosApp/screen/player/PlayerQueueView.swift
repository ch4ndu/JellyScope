// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct PlayerQueueView: View {
    let queue: TvPlaybackQueueState
    let onShuffle: () -> Void
    let onSelectItem: (Int) -> Void
    let onPrevious: () -> Void
    let onNext: () -> Void
    let onClose: () -> Void
    @FocusState private var focusedItemId: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Text("Play Queue")
                    .font(.title2.bold())
                Spacer()
                Button("Shuffle") {
                    onShuffle()
                }
            }

            ScrollView {
                LazyVStack(spacing: 12) {
                    ForEach(queue.items, id: \.media.id) { item in
                        Button {
                            onSelectItem(Int(item.index))
                            onClose()
                        } label: {
                            HStack(spacing: 16) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(item.media.title)
                                        .font(.headline)
                                    if let detail = item.media.episodeLabel ?? item.media.seriesName {
                                        Text(detail)
                                            .font(.subheadline)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                                if item.isCurrent {
                                    Label("Now Playing", systemImage: "speaker.wave.2.fill")
                                        .foregroundStyle(.tint)
                                }
                            }
                            .padding(.horizontal, 18)
                            .padding(.vertical, 14)
                        }
                        .buttonStyle(.bordered)
                        .focused($focusedItemId, equals: item.media.id)
                    }
                }
            }

            HStack {
                Button("Previous") { onPrevious(); onClose() }
                    .disabled(!queue.hasPrevious)
                Button("Next") { onNext(); onClose() }
                    .disabled(!queue.hasNext)
                Spacer()
                Button("Done", action: onClose)
            }
        }
        .padding(36)
        .frame(width: 760, height: 760)
        .background(.black.opacity(0.92), in: RoundedRectangle(cornerRadius: 22))
        .onAppear {
            focusedItemId = queue.items.first(where: { $0.isCurrent })?.media.id
        }
    }
}
