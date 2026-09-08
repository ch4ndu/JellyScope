// SPDX-License-Identifier: MPL-2.0

import SharedTv
import SwiftUI

struct PlayerQueueView: View {
    @ObservedObject var model: PlaybackModel
    let onClose: () -> Void
    @FocusState private var focusedItemId: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Text("Play Queue")
                    .font(.title2.bold())
                Spacer()
                Button("Shuffle") {
                    model.shuffleQueue()
                }
            }

            ScrollView {
                LazyVStack(spacing: 12) {
                    ForEach(model.queue.items, id: \.media.id) { item in
                        Button {
                            model.selectQueueItem(Int(item.index))
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
                Button("Previous") { model.playPreviousEpisode(); onClose() }
                    .disabled(!model.queue.hasPrevious)
                Button("Next") { model.playNextEpisode(); onClose() }
                    .disabled(!model.queue.hasNext)
                Spacer()
                Button("Done", action: onClose)
            }
        }
        .padding(36)
        .frame(width: 760, height: 760)
        .background(.black.opacity(0.92), in: RoundedRectangle(cornerRadius: 22))
        .onAppear {
            focusedItemId = model.queue.items.first(where: { $0.isCurrent })?.media.id
        }
    }
}
