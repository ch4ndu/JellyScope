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
        presenter.setQuery(query: query)
    }

    func submit() {
        presenter.submit(query: displayedQuery)
    }

    func selectRecent(_ query: String) {
        programmaticQueryEcho = nil
        displayedQuery = query
        presenter.submit(query: query)
    }

    func toggleGenre(_ genre: TvSearchGenre) {
        presenter.toggleGenre(genre: genre)
    }

    func setRuntimeBucket(_ bucket: RuntimeBucket) {
        presenter.setRuntimeBucket(bucket: bucket)
    }

    func setWatchedFilter(_ filter: WatchedFilter) {
        presenter.setWatchedFilter(filter: filter)
    }

    func selectPerson(_ person: TvSearchPerson) {
        programmaticQueryEcho = person.name
        displayedQuery = person.name
        presenter.selectPerson(person: person)
    }

    func clearPerson() {
        programmaticQueryEcho = nil
        displayedQuery = ""
        presenter.clearPerson()
    }

    func selectResultCategory(_ category: TvSearchResultCategory) {
        presenter.selectResultCategory(category: category)
    }

    func retry() {
        presenter.retry()
    }

    func clearRecents() {
        presenter.clearRecents()
    }

    func viewAppeared() {
        if hasAppeared {
            presenter.refresh()
        } else {
            hasAppeared = true
        }
    }

    deinit {
        handle?.close()
        presenter.close()
    }
}
