// SPDX-License-Identifier: MPL-2.0

import Foundation
import SharedTv

@MainActor
final class SearchModel: ObservableObject {
    @Published private(set) var state: TvSearchState
    @Published private(set) var displayedQuery = ""

    private let presenter: TvSearchPresenter
    private var handle: WatchHandle?
    private var hasAppeared = false
    private var programmaticQueryEcho: String?

    init(session: Session) {
        let presenter = TvosEntry.shared.searchPresenter(session: session)
        self.presenter = presenter
        self.state = presenter.state.value as! TvSearchState
        self.handle = presenter.watchState { [weak self] state in
            self?.state = state
        }
    }

    func userQueryChanged(_ query: String) {
        displayedQuery = query
        if query == programmaticQueryEcho {
            programmaticQueryEcho = nil
            return
        }
        programmaticQueryEcho = nil
        presenter.accept(intent: TvSearchIntentSetQuery(query: query))
    }

    func submit() {
        presenter.accept(intent: TvSearchIntentSubmit(query: displayedQuery))
    }

    func selectRecent(_ query: String) {
        programmaticQueryEcho = nil
        displayedQuery = query
        presenter.accept(intent: TvSearchIntentSubmit(query: query))
    }

    func toggleGenre(_ genre: TvSearchGenre) {
        presenter.accept(intent: TvSearchIntentToggleGenre(genre: genre))
    }

    func setRuntimeBucket(_ bucket: RuntimeBucket) {
        presenter.accept(intent: TvSearchIntentSetRuntimeBucket(bucket: bucket))
    }

    func setWatchedFilter(_ filter: WatchedFilter) {
        presenter.accept(intent: TvSearchIntentSetWatchedFilter(filter: filter))
    }

    func selectPerson(_ person: TvSearchPerson) {
        programmaticQueryEcho = person.name
        displayedQuery = person.name
        presenter.accept(intent: TvSearchIntentSelectPerson(person: person))
    }

    func clearPerson() {
        programmaticQueryEcho = nil
        displayedQuery = ""
        presenter.accept(intent: TvSearchIntentClearPerson.shared)
    }

    func selectResultCategory(_ category: TvSearchResultCategory) {
        presenter.accept(intent: TvSearchIntentSelectResultCategory(category: category))
    }

    func retry() {
        presenter.accept(intent: TvSearchIntentRetry.shared)
    }

    func clearRecents() {
        presenter.accept(intent: TvSearchIntentClearRecents.shared)
    }

    func viewAppeared() {
        if hasAppeared {
            presenter.accept(intent: TvSearchIntentRefresh.shared)
        } else {
            hasAppeared = true
        }
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}
