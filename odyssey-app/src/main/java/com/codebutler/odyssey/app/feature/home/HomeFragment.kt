/*
 * BrowseFragment.kt
 *
 * Copyright (C) 2017 Odyssey Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.codebutler.odyssey.app.feature.home

import android.arch.lifecycle.Observer
import android.arch.paging.PagedList
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v17.leanback.app.BrowseFragment
import android.support.v17.leanback.app.BrowseSupportFragment
import android.support.v17.leanback.widget.ArrayObjectAdapter
import android.support.v17.leanback.widget.HeaderItem
import android.support.v17.leanback.widget.ListRow
import android.support.v17.leanback.widget.ListRowPresenter
import android.support.v17.leanback.widget.OnItemViewClickedListener
import android.support.v4.app.ActivityOptionsCompat
import com.codebutler.odyssey.R
import com.codebutler.odyssey.app.feature.game.GameActivity
import com.codebutler.odyssey.app.feature.search.GamesSearchFragment
import com.codebutler.odyssey.app.feature.settings.SettingsActivity
import com.codebutler.odyssey.lib.library.GameLibrary
import com.codebutler.odyssey.lib.library.GameSystem
import com.codebutler.odyssey.lib.library.db.OdysseyDatabase
import com.codebutler.odyssey.lib.library.db.entity.Game
import com.codebutler.odyssey.lib.ui.PagedListObjectAdapter
import com.codebutler.odyssey.lib.ui.SimpleErrorFragment
import com.codebutler.odyssey.lib.ui.SimpleItem
import com.codebutler.odyssey.lib.ui.SimpleItemPresenter
import com.uber.autodispose.android.lifecycle.AndroidLifecycleScopeProvider
import com.uber.autodispose.kotlin.autoDisposeWith
import dagger.Subcomponent
import dagger.android.AndroidInjector
import dagger.android.support.AndroidSupportInjection
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import javax.inject.Inject

class HomeFragment : BrowseSupportFragment() {

    private data class GameSystemItem(val system: GameSystem) : SimpleItem(system.titleResId)
    private object AboutItem : SimpleItem(R.string.about)
    private object RescanItem : SimpleItem(R.string.rescan)
    private object AllGamesItem : SimpleItem(R.string.all_games)
    private object SettingsItem : SimpleItem(R.string.settings)

    @Inject lateinit var gameLibrary: GameLibrary
    @Inject lateinit var odysseyDb: OdysseyDatabase

    override fun onAttach(context: Context?) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        headersState = BrowseFragment.HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        title = getString(R.string.app_name)

        setOnSearchClickedListener {
            fragmentManager.beginTransaction()
                    .replace(R.id.content, GamesSearchFragment.create())
                    .addToBackStack(null)
                    .commit()
        }

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is Game -> startActivity(GameActivity.newIntent(context, item))
                is GameSystemItem -> fragmentManager.beginTransaction()
                        .replace(R.id.content, GamesGridFragment.create(GamesGridFragment.Mode.SYSTEM, item.system.id))
                        .addToBackStack(null)
                        .commit()
                is AllGamesItem -> fragmentManager.beginTransaction()
                        .replace(R.id.content, GamesGridFragment.create(GamesGridFragment.Mode.ALL))
                        .addToBackStack(null)
                        .commit()
                is RescanItem -> {
                    progressBarManager.show()
                    gameLibrary.indexGames()
                            .observeOn(AndroidSchedulers.mainThread())
                            .autoDisposeWith(AndroidLifecycleScopeProvider.from(this))
                            .subscribe(
                                    {
                                        loadContents()
                                        progressBarManager.hide()
                                    },
                                    { error ->
                                        progressBarManager.hide()
                                        val errorFragment = SimpleErrorFragment.create(error.toString())
                                        fragmentManager.beginTransaction()
                                                .replace(R.id.content, errorFragment)
                                                .addToBackStack(null)
                                                .commit()
                                    })
                }
                is SettingsItem -> {
                    val intent = Intent(activity, SettingsActivity::class.java)
                    val bundle = ActivityOptionsCompat.makeSceneTransitionAnimation(activity)
                            .toBundle()
                    startActivity(intent, bundle)
                }
                is AboutItem -> {}
            }
        }

        loadContents()
    }

    private fun loadContents() {
        val favoritesAdapter = PagedListObjectAdapter(GamePresenter(), Game.DIFF_CALLBACK)
        odysseyDb.gameDao().selectFavorites()
                .create(0, PagedList.Config.Builder()
                        .setPageSize(50)
                        .setPrefetchDistance(50)
                        .build())
                .observe(this, Observer {
                    pagedList -> favoritesAdapter.pagedList = pagedList
                })

        val recentsAdapter = PagedListObjectAdapter(GamePresenter(), Game.DIFF_CALLBACK)
        odysseyDb.gameDao().selectRecentlyPlayed()
                .create(0, PagedList.Config.Builder()
                        .setPageSize(50)
                        .setPrefetchDistance(50)
                        .build())
                .observe(this, Observer {
                    pagedList -> recentsAdapter.pagedList = pagedList
                })

        val systemsAdapter = ArrayObjectAdapter(SimpleItemPresenter())
        odysseyDb.gameDao().selectSystems()
                .toObservable()
                .flatMapIterable { it }
                .map { GameSystem.findById(it)!! }
                .toList()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .autoDisposeWith(AndroidLifecycleScopeProvider.from(this))
                .subscribe { systems ->
                    systemsAdapter.clear()
                    for (system in systems) {
                        systemsAdapter.add(GameSystemItem(system))
                    }
                    systemsAdapter.add(AllGamesItem)
                }

        val settingsAdapter = ArrayObjectAdapter(SimpleItemPresenter()).apply {
            add(SettingsItem)
            add(RescanItem)
            add(AboutItem)
        }

        val categoryRowAdapter = ArrayObjectAdapter(ListRowPresenter())
        categoryRowAdapter.add(ListRow(HeaderItem(getString(R.string.favorites)), favoritesAdapter))
        categoryRowAdapter.add(ListRow(HeaderItem(getString(R.string.recently_played)), recentsAdapter))
        categoryRowAdapter.add(ListRow(HeaderItem(getString(R.string.library)), systemsAdapter))
        categoryRowAdapter.add(ListRow(HeaderItem(getString(R.string.settings)), settingsAdapter))
        adapter = categoryRowAdapter
    }

    @Subcomponent
    interface Component : AndroidInjector<HomeFragment> {

        @Subcomponent.Builder
        abstract class Builder : AndroidInjector.Builder<HomeFragment>()
    }
}