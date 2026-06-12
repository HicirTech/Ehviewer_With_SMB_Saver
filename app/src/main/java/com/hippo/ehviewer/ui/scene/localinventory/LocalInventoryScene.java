/*
 * Copyright 2024 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.hippo.android.resource.AttrResources;
import com.hippo.easyrecyclerview.EasyRecyclerView;
import com.hippo.easyrecyclerview.FastScroller;
import com.hippo.easyrecyclerview.HandlerDrawable;
import com.hippo.easyrecyclerview.MarginItemDecoration;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.smb.SmbCoverDataContainer;
import com.hippo.ehviewer.smb.SmbStorage;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.ui.scene.ToolbarScene;
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene;
import com.hippo.ehviewer.widget.SimpleRatingView;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.lib.yorozuya.ViewUtils;
import com.hippo.ripple.Ripple;
import com.hippo.scene.Announcer;
import com.hippo.util.DrawableManager;
import com.hippo.view.ViewTransition;
import com.hippo.widget.LoadImageView;
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Browses galleries that were saved to the SMB share. Completely independent of
 * {@code FavoritesScene} — this scene only renders content read from
 * {@link SmbStorage#loadInventory()} and never touches Eh favorites state.
 */
public class LocalInventoryScene extends ToolbarScene
        implements EasyRecyclerView.OnItemClickListener {

    @Nullable
    private EasyRecyclerView mRecyclerView;
    @Nullable
    private ViewTransition mViewTransition;
    @Nullable
    private InventoryAdapter mAdapter;
    @Nullable
    private TextView mTip;

    private final List<GalleryInfo> mList = new ArrayList<>();
    private volatile boolean mLoading;
    @Nullable
    private ExecutorService mExecutor;

    @Override
    public int getNavCheckedItem() {
        return R.id.nav_local_inventory;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context context = getEHContext();
        if (context != null) {
            mExecutor = EhApplication.getExecutorService(context);
        }
    }

    @Nullable
    @Override
    public View onCreateView3(LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.scene_local_inventory, container, false);
        View content = ViewUtils.$$(view, R.id.content);
        mRecyclerView = (EasyRecyclerView) ViewUtils.$$(content, R.id.recycler_view);
        FastScroller fastScroller = (FastScroller) ViewUtils.$$(content, R.id.fast_scroller);
        mTip = (TextView) ViewUtils.$$(view, R.id.tip);
        mViewTransition = new ViewTransition(content, mTip);

        Context context = getEHContext();
        if (context == null) return view;
        Resources resources = context.getResources();

        // Match the empty-view "sad pandroid" style used by ContentLayout-based scenes
        // (FavoritesScene, GalleryListScene...): a centered TextView with the vector
        // sadroid drawable as the compound top icon.
        Drawable sadDrawable = DrawableManager.getVectorDrawable(context, R.drawable.big_sad_pandroid);
        if (sadDrawable != null) {
            sadDrawable.setBounds(0, 0, sadDrawable.getIntrinsicWidth(), sadDrawable.getIntrinsicHeight());
            mTip.setCompoundDrawables(null, sadDrawable, null, null);
        }

        mAdapter = new InventoryAdapter();
        mAdapter.setHasStableIds(true);
        mRecyclerView.setAdapter(mAdapter);

        AutoStaggeredGridLayoutManager layoutManager = new AutoStaggeredGridLayoutManager(
                0, StaggeredGridLayoutManager.VERTICAL);
        layoutManager.setColumnSize(resources.getDimensionPixelOffset(Settings.getDetailSizeResId()));
        layoutManager.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_MIN_SIZE);
        mRecyclerView.setLayoutManager(layoutManager);
        mRecyclerView.setSelector(Ripple.generateRippleDrawable(
                context,
                !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme),
                new ColorDrawable(Color.TRANSPARENT)));
        mRecyclerView.setDrawSelectorOnTop(true);
        mRecyclerView.setClipToPadding(false);
        mRecyclerView.setOnItemClickListener(this);

        int interval = resources.getDimensionPixelOffset(R.dimen.gallery_list_interval);
        int paddingH = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_h);
        int paddingV = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_v);
        MarginItemDecoration decoration = new MarginItemDecoration(interval, paddingH, paddingV, paddingH, paddingV);
        mRecyclerView.addItemDecoration(decoration);
        decoration.applyPaddings(mRecyclerView);

        fastScroller.attachToRecyclerView(mRecyclerView);
        HandlerDrawable handlerDrawable = new HandlerDrawable();
        handlerDrawable.setColor(AttrResources.getAttrColor(context, R.attr.widgetColorThemeAccent));
        fastScroller.setHandlerDrawable(handlerDrawable);

        com.google.android.material.floatingactionbutton.FloatingActionButton sortFab =
                (com.google.android.material.floatingactionbutton.FloatingActionButton)
                        ViewUtils.$$(view, R.id.sort_fab);
        sortFab.setOnClickListener(v -> showSortDialog());

        showLoadingState();
        reload();
        return view;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setTitle(R.string.local_inventory);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public int getMenuResId() {
        return R.menu.scene_local_inventory;
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh) {
            showLoadingState();
            reload();
            return true;
        }
        if (id == R.id.action_smb_tasks) {
            startScene(new Announcer(SmbDownloadTasksScene.class));
            return true;
        }
        return false;
    }

    @Override
    public void onNavigationClick(View view) {
        onBackPressed();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mRecyclerView != null) {
            mRecyclerView.stopScroll();
            mRecyclerView = null;
        }
        mViewTransition = null;
        mAdapter = null;
        mTip = null;
    }

    private void showLoadingState() {
        if (mTip != null) {
            mTip.setText(R.string.local_inventory_loading);
        }
        if (mViewTransition != null && (mAdapter == null || mAdapter.getItemCount() == 0)) {
            mViewTransition.showView(1, false);
        }
    }

    private void showSortDialog() {
        Context context = getEHContext();
        if (context == null) {
            return;
        }
        int current = Settings.getLocalInventorySort();
        new AlertDialog.Builder(context)
                .setTitle(R.string.local_inventory_sort_title)
                .setSingleChoiceItems(R.array.local_inventory_sort, current, (dialog, which) -> {
                    if (which != Settings.getLocalInventorySort()) {
                        Settings.putLocalInventorySort(which);
                        showLoadingState();
                        reload();
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void reload() {
        if (mLoading) {
            return;
        }
        mLoading = true;
        final SmbStorage.SortMode mode = SmbStorage.SortMode.fromOrdinal(Settings.getLocalInventorySort());
        Runnable task = () -> {
            final List<GalleryInfo> loaded;
            try {
                // Issue #2644 requires a 7s timeout — if the share can't be reached by then,
                // surface the sad-pandroid error state instead of hanging the scene indefinitely.
                // We can't bound jcifs's internal socket timeouts without rebuilding the global
                // SingletonContext, so we wrap the actual load in a Future with a hard cap.
                java.util.concurrent.ExecutorService pool =
                        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                            Thread t = new Thread(r, "smb-inventory-load");
                            t.setDaemon(true);
                            return t;
                        });
                Future<List<GalleryInfo>> fut = pool.submit(() -> SmbStorage.loadInventory(mode));
                try {
                    loaded = fut.get(7, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    fut.cancel(true);
                    throw new java.io.IOException(getString(R.string.local_inventory_timeout));
                } finally {
                    pool.shutdownNow();
                }
            } catch (Throwable e) {
                SimpleHandler.getInstance().post(() -> {
                    mLoading = false;
                    if (mTip != null) {
                        mTip.setText(getString(R.string.local_inventory_error, e.getMessage()));
                    }
                    if (mViewTransition != null) {
                        mViewTransition.showView(1, true);
                    }
                });
                return;
            }
            SimpleHandler.getInstance().post(() -> {
                mLoading = false;
                mList.clear();
                if (loaded != null) {
                    mList.addAll(loaded);
                    // Pre-mark every SMB-saved gid so any read path (cover load, detail
                    // navigation, reader launch) routes through SmbStorage rather than
                    // looking on phone storage.
                    for (GalleryInfo gi : loaded) {
                        SmbStorage.markGidAsSmbTarget(gi.gid);
                    }
                }
                if (mAdapter != null) {
                    mAdapter.notifyDataSetChanged();
                }
                if (mViewTransition != null) {
                    if (mList.isEmpty()) {
                        if (mTip != null) {
                            if (!SmbStorage.isConfigured() || !Settings.getSmbSaveEnabled()) {
                                mTip.setText(R.string.local_inventory_disabled);
                            } else {
                                mTip.setText(R.string.local_inventory_empty);
                            }
                        }
                        mViewTransition.showView(1, true);
                    } else {
                        mViewTransition.showView(0, true);
                    }
                }
            });
        };
        if (mExecutor != null) {
            mExecutor.execute(task);
        } else {
            new Thread(task, "LocalInventoryLoader").start();
        }
    }

    @Override
    public boolean onItemClick(EasyRecyclerView parent, View view, int position, long id) {
        if (position < 0 || position >= mList.size()) {
            return false;
        }
        GalleryInfo gi = mList.get(position);
        openDetail(gi);
        return true;
    }

    private void openDetail(@Nullable GalleryInfo gi) {
        if (gi == null) {
            return;
        }
        Bundle args = new Bundle();
        args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GALLERY_INFO);
        args.putParcelable(GalleryDetailScene.KEY_GALLERY_INFO, gi);
        // Render fully from local SMB metadata. Reconstructs tags from tgList so the
        // detail page does not need a network call.
        args.putParcelable(GalleryDetailScene.KEY_GALLERY_DETAIL, SmbStorage.buildOfflineDetail(gi));
        // SMB metadata never carries comments — hide that section entirely.
        args.putBoolean(GalleryDetailScene.KEY_HIDE_COMMENTS, true);
        startScene(new Announcer(GalleryDetailScene.class).setArgs(args));

        // Older entries may have been written before tag enrichment was added.
        // Opportunistically fetch detail in the background and rewrite metadata so the
        // next open is also fully offline.
        Context context = getEHContext();
        if (context != null && (gi.tgList == null || gi.tgList.isEmpty())) {
            SmbStorage.enrichLocalMetadataIfMissing(context, gi);
        }
    }

    private void openReader(@Nullable GalleryInfo gi) {
        if (gi == null) {
            return;
        }
        Context context = getEHContext();
        if (context == null) {
            return;
        }
        // Mark the gid so SpiderDen routes reads (cover/spider info/pages) to SMB instead
        // of looking on phone storage.
        SmbStorage.markGidAsSmbTarget(gi.gid);
        Intent intent = new Intent(context, GalleryActivity.class);
        intent.setAction(GalleryActivity.ACTION_EH);
        intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, gi);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private final class InventoryHolder extends RecyclerView.ViewHolder {
        final LoadImageView thumb;
        final TextView title;
        final TextView uploader;
        final SimpleRatingView rating;
        final TextView category;
        final TextView posted;
        final TextView simpleLanguage;
        final TextView pages;

        InventoryHolder(View itemView) {
            super(itemView);
            thumb = (LoadImageView) itemView.findViewById(R.id.thumb);
            title = (TextView) itemView.findViewById(R.id.title);
            uploader = (TextView) itemView.findViewById(R.id.uploader);
            rating = (SimpleRatingView) itemView.findViewById(R.id.rating);
            category = (TextView) itemView.findViewById(R.id.category);
            posted = (TextView) itemView.findViewById(R.id.posted);
            simpleLanguage = (TextView) itemView.findViewById(R.id.simple_language);
            pages = (TextView) itemView.findViewById(R.id.pages);
        }
    }

    private final class InventoryAdapter extends RecyclerView.Adapter<InventoryHolder> {
        @Override
        public long getItemId(int position) {
            if (position < 0 || position >= mList.size()) return RecyclerView.NO_ID;
            return mList.get(position).gid;
        }

        @Override
        public InventoryHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = getLayoutInflater2().inflate(R.layout.item_gallery_list, parent, false);
            return new InventoryHolder(v);
        }

        @Override
        public void onBindViewHolder(InventoryHolder holder, int position) {
            GalleryInfo gi = mList.get(position);
            // Route the cover load through SmbCoverDataContainer so Conaco reads cover.<ext>
            // straight from the SMB share (saved alongside the gallery at download time)
            // instead of hitting e-hentai for the thumbnail URL. useNetwork=false makes the
            // load offline-only — if the on-share cover is missing the cell just stays empty
            // rather than silently leaking out to the network.
            holder.thumb.load(EhCacheKeyFactory.getThumbKey(gi.gid),
                    gi.thumb != null ? gi.thumb : ("smb-cover://" + gi.gid),
                    new SmbCoverDataContainer(gi.gid, gi.title), false, false);
            // Tap the thumbnail to jump straight into the reader (offline-friendly path).
            // Tapping anywhere else on the card opens the gallery detail page (handled by the
            // RecyclerView's OnItemClickListener).
            holder.thumb.setOnClickListener(v -> openReader(gi));
            holder.title.setText(EhUtils.getSuitableTitle(gi));
            holder.uploader.setText(gi.uploader);
            holder.rating.setRating(gi.rating);
            String catText = EhUtils.getCategory(gi.category);
            holder.category.setText(catText);
            holder.category.setBackgroundColor(EhUtils.getCategoryColor(gi.category));
            holder.posted.setText(gi.posted);
            holder.simpleLanguage.setText(gi.simpleLanguage);
            if (gi.pages > 0) {
                holder.pages.setText(getResources().getQuantityString(R.plurals.page_count, gi.pages, gi.pages));
            } else {
                holder.pages.setText(null);
            }
        }

        @Override
        public int getItemCount() {
            return mList.size();
        }
    }
}
