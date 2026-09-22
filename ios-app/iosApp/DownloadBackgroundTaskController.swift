// SPDX-License-Identifier: MPL-2.0

import BackgroundTasks
import Foundation
import SharedUi
import UIKit

/// Main-queue owner of iOS 26 continued-processing task objects for existing Kotlin downloads.
final class DownloadBackgroundTaskController: NSObject, IosDownloadBackgroundTaskCallbacks {
    /// One application-process owner. Scenes only receive this retained instance.
    static let shared = DownloadBackgroundTaskController()

    /// Constructed only after Compose has initialized Koin in MainViewController.
    private var bridge: IosDownloadBackgroundBridge?
    private var installed = false
    private var title: String?
    private var subtitle: String?
    /// A generation that is awaiting labels and has not reached the scheduler.
    private var pendingGeneration: Int64?
    /// A generation submitted to BackgroundTasks but not yet delivered to its handler.
    private var submittedGeneration: Int64?
    /// BGTask is available at the iOS 16 deployment floor; iOS 26-only casts stay scoped.
    private var activeTask: BGTask?
    private var activeGeneration: Int64?

    init(bridge: IosDownloadBackgroundBridge? = nil) {
        self.bridge = bridge
        super.init()
    }

    /// Installs one wildcard handler before any Downloads action can request native admission.
    func install() {
        dispatchPrecondition(condition: .onQueue(.main))
        guard !installed else { return }
        installed = true
        let installedBridge = bridge ?? IosDownloadBackgroundBridge()
        bridge = installedBridge
        installedBridge.install(callbacks: self)
        guard #available(iOS 26.0, *), let wildcardIdentifier else { return }
        _ = BGTaskScheduler.shared.register(
            forTaskWithIdentifier: wildcardIdentifier,
            using: .main
        ) { [weak self] task in
            self?.receive(task: task)
        }
    }

    func configureContinuationLabels(title: String, subtitle: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.title = title
            self.subtitle = subtitle
            if let generation = self.pendingGeneration {
                self.submitIfAvailable(generation: generation)
            }
        }
    }

    func requestContinuation(wakeGeneration: Int64) {
        DispatchQueue.main.async { [weak self] in
            self?.submitIfAvailable(generation: wakeGeneration)
        }
    }

    func reportProgress(
        wakeGeneration: Int64,
        transferredBytes: Int64,
        expectedBytes: KotlinLong?
    ) {
        DispatchQueue.main.async { [weak self] in
            guard
                let self,
                #available(iOS 26.0, *),
                self.activeGeneration == wakeGeneration,
                let task = self.activeTask as? BGContinuedProcessingTask
            else {
                return
            }
            guard let expectedBytes else {
                task.progress.totalUnitCount = 0
                task.progress.completedUnitCount = 0
                return
            }
            let total = max(expectedBytes.int64Value, 1)
            task.progress.totalUnitCount = total
            task.progress.completedUnitCount = min(max(transferredBytes, 0), total)
        }
    }

    func finishContinuation(wakeGeneration: Int64, succeeded: Bool) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.cancelPendingRequest(generation: wakeGeneration)
            guard #available(iOS 26.0, *) else { return }
            self.finishActiveTask(generation: wakeGeneration, succeeded: succeeded)
        }
    }

    private var wildcardIdentifier: String? = Bundle.main.bundleIdentifier.map { "\($0).downloads.*" }

    private func identifier(for generation: Int64) -> String? {
        Bundle.main.bundleIdentifier.map { "\($0).downloads.\(generation)" }
    }

    private func submitIfAvailable(generation: Int64) {
        dispatchPrecondition(condition: .onQueue(.main))
        if submittedGeneration == generation {
            return
        }
        if pendingGeneration != generation {
            if let pendingGeneration {
                cancelPendingRequest(generation: pendingGeneration)
            }
            if let submittedGeneration {
                cancelPendingRequest(generation: submittedGeneration)
            }
            pendingGeneration = generation
        }
        guard #available(iOS 26.0, *) else {
            rejectPendingGeneration(generation)
            return
        }
        guard let identifier = identifier(for: generation) else {
            rejectPendingGeneration(generation)
            return
        }
        guard let title, let subtitle else { return }
        guard UIApplication.shared.applicationState == .active else {
            rejectPendingGeneration(generation)
            return
        }
        // Submission itself grants no permission. Ask Kotlin immediately before
        // the scheduler call so a delayed callback can never revive stale work.
        guard bridge?.canGrant(wakeGeneration: generation) == true else {
            rejectPendingGeneration(generation)
            return
        }
        let request = BGContinuedProcessingTaskRequest(
            identifier: identifier,
            title: title,
            subtitle: subtitle
        )
        request.strategy = .fail
        do {
            submittedGeneration = generation
            pendingGeneration = nil
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // Submission does not authorize background execution. Kotlin keeps
            // the foreground writer running and rejects any stale callback.
            rejectPendingGeneration(generation)
        }
    }

    @available(iOS 26.0, *)
    private func receive(task: BGTask) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard
            let continuedTask = task as? BGContinuedProcessingTask,
            let generation = generation(from: continuedTask.identifier)
        else {
            task.setTaskCompleted(success: false)
            return
        }
        guard bridge?.canGrant(wakeGeneration: generation) == true else {
            if submittedGeneration == generation {
                submittedGeneration = nil
            }
            task.setTaskCompleted(success: false)
            return
        }
        let previousTask = activeTask
        let previousGeneration = activeGeneration
        activeTask = continuedTask
        activeGeneration = generation
        if pendingGeneration == generation {
            pendingGeneration = nil
        }
        if submittedGeneration == generation {
            submittedGeneration = nil
        }
        continuedTask.expirationHandler = { [weak self] in
            self?.bridge?.expire(wakeGeneration: generation)
        }
        guard bridge?.grant(wakeGeneration: generation) == true else {
            activeTask = previousTask
            activeGeneration = previousGeneration
            continuedTask.expirationHandler = nil
            continuedTask.setTaskCompleted(success: false)
            return
        }
        if let previousTask {
            previousTask.expirationHandler = nil
            previousTask.setTaskCompleted(success: false)
        }
    }

    private func cancelPendingRequest(generation: Int64) {
        if #available(iOS 26.0, *), let identifier = identifier(for: generation) {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier)
        }
        if pendingGeneration == generation {
            pendingGeneration = nil
        }
        if submittedGeneration == generation {
            submittedGeneration = nil
        }
    }

    private func rejectPendingGeneration(_ generation: Int64) {
        cancelPendingRequest(generation: generation)
        bridge?.reject(wakeGeneration: generation)
    }

    private func finishActiveTask(generation: Int64, succeeded: Bool) {
        guard activeGeneration == generation, let task = activeTask else { return }
        activeTask = nil
        activeGeneration = nil
        task.expirationHandler = nil
        task.setTaskCompleted(success: succeeded)
    }

    @available(iOS 26.0, *)
    private func generation(from identifier: String) -> Int64? {
        let prefix = "\(Bundle.main.bundleIdentifier ?? "").downloads."
        guard identifier.hasPrefix(prefix) else { return nil }
        return Int64(identifier.dropFirst(prefix.count))
    }
}
