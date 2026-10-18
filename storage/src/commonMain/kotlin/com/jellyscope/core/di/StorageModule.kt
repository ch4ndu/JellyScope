// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.di

import com.jellyscope.core.data.local.DownloadDatabase
import com.jellyscope.core.data.local.DownloadRecordStore
import com.jellyscope.core.data.local.DownloadRemovalStore
import com.jellyscope.core.data.local.DownloadSettingsStore
import com.jellyscope.core.data.local.RoomDownloadRecordStore
import com.jellyscope.core.data.local.RoomDownloadRemovalStore
import com.jellyscope.core.data.local.RoomDownloadSettingsStore
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

@OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
@kotlin.native.HiddenFromObjC
val localSubtitleSelectionPersistenceQualifier = named("LocalSubtitleSelectionPersistence")

@OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
@kotlin.native.HiddenFromObjC
fun downloadStorageModule(): Module =
    module {
        single { get<DownloadDatabase>().downloadDao() }
        single<DownloadSettingsStore> { RoomDownloadSettingsStore(dao = get()) }
        single<DownloadRecordStore> { RoomDownloadRecordStore(dao = get()) }
        single<DownloadRemovalStore> { RoomDownloadRemovalStore(dao = get()) }
    }
