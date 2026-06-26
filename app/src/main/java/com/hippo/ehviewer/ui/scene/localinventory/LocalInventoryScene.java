/*
 * Copyright 2024 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.hippo.android.resource.AttrResources;
import com.hippo.easyrecyclerview.EasyRecyclerView;
import com.hippo.easyrecyclerview.MarginItemDecoration;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.smb.SmbCoverDataContainer;
import com.hippo.ehviewer.smb.SmbMetadata;
import com.hippo.ehviewer.smb.SmbPaths;
import com.hippo.ehviewer.smb.SmbSortMode;
import com.hippo.ehviewer.smb.SmbStorage;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.ui.scene.ToolbarScene;
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene;
import com.hippo.ehviewer.widget.GalleryInfoContentHelper;
import com.hippo.ehviewer.widget.SimpleRatingView;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.lib.yorozuya.ViewUtils;
import com.hippo.ripple.Ripple;
import com.hippo.scene.Announcer;
import com.hippo.widget.ContentLayout;
import com.hippo.widget.LoadImageView;
import com.hippo.widget.Slider;
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Browses galleries that were saved to the SMB share. Completely independent of
 * {@code FavoritesScene} — this scene only renders content read from the share and never touches Eh
 * favorites state.
 *
 * <p>Paginates exactly like the online gallery list: it drives a {@link ContentLayout} through a
 * {@link ContentLayout.ContentHelper}, so it gets the same page-by-page navigation (pull for
 * next/prev page, "go to page" jump) and the same data/scroll retention on return from a detail.
 * Each page reads only its own slice of {@code metadata.json} files, so a big share never blocks on
 * a full up-front sweep.
 */
public class LocalInventoryScene extends ToolbarScene
        implements EasyRecyclerView.OnItemClickListener {

    // Galleries read per page. Bounds the SMB metadata reads done before a page can render.
    private static final int PAGE_SIZE = 50;

    @Nullable
    private EasyRecyclerView mRecyclerView;
    @Nullable
    private InventoryAdapter mAdapter;
    @Nullable
    private InventoryHelper mHelper;
    private boolean mHasFirstRefresh;
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
        ContentLayout contentLayout = (ContentLayout) ViewUtils.$$(view, R.id.content_layout);
        mRecyclerView = contentLayout.getRecyclerView();

        Context context = getEHContext();
        if (context == null) return view;
        Resources resources = context.getResources();

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

        mHelper = new InventoryHelper();
        mHelper.setEmptyString(getEmptyString());
        contentLayout.setHelper(mHelper);

        com.google.android.material.floatingactionbutton.FloatingActionButton sortFab =
                (com.google.android.material.floatingactionbutton.FloatingActionButton)
                        ViewUtils.$$(view, R.id.sort_fab);
        sortFab.setOnClickListener(v -> showSortDialog());

        // Only the first time. On return from a detail the ContentLayout restores its data and scroll
        // position from saved view state, exactly like the online gallery list.
        if (!mHasFirstRefresh) {
            mHasFirstRefresh = true;
            mHelper.firstRefresh();
        }
        return view;
    }

    @NonNull
    private String getEmptyString() {
        if (!SmbStorage.isConfigured() || !Settings.getSmbSaveEnabled()) {
            return getString(R.string.local_inventory_disabled);
        }
        return getString(R.string.local_inventory_empty);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setTitle(R.string.local_inventory);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
    }

    @Override
    public int getMenuResId() {
        return R.menu.scene_local_inventory;
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh) {
            if (mHelper != null) {
                mHelper.refresh();
            }
            return true;
        }
        if (id == R.id.action_go_to) {
            showGoToDialog();
            return true;
        }
        if (id == R.id.action_smb_tasks) {
            startScene(new Announcer(SmbDownloadTasksScene.class));
            return true;
        }
        return false;
    }

    private void showGoToDialog() {
        Context context = getEHContext();
        if (context == null || mHelper == null) {
            return;
        }
        int pages = mHelper.getPages();
        if (pages <= 0 || !mHelper.canGoTo()) {
            return;
        }
        GoToDialogHelper helper = new GoToDialogHelper(pages, mHelper.getPageForTop());
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.go_to)
                .setView(R.layout.dialog_go_to)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.show();
        helper.setDialog(dialog);
    }

    @Override
    public void onNavigationClick(View view) {
        onBackPressed();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mHelper != null) {
            // Drop the favourite-status listener registered by GalleryInfoContentHelper. If the share
            // is currently empty, allow a fresh first refresh next time the view is created.
            if (1 == mHelper.getShownViewIndex()) {
                mHasFirstRefresh = false;
            }
            mHelper.destroy();
            mHelper = null;
        }
        if (mRecyclerView != null) {
            mRecyclerView.stopScroll();
            mRecyclerView = null;
        }
        mAdapter = null;
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
                        if (mHelper != null) {
                            mHelper.refresh();
                        }
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public boolean onItemClick(EasyRecyclerView parent, View view, int position, long id) {
        if (mHelper == null) {
            return false;
        }
        GalleryInfo gi = mHelper.getDataAtEx(position);
        if (gi == null) {
            return false;
        }
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
        args.putParcelable(GalleryDetailScene.KEY_GALLERY_DETAIL, SmbMetadata.buildOfflineDetail(gi));
        // SMB metadata never carries comments — hide that section entirely.
        args.putBoolean(GalleryDetailScene.KEY_HIDE_COMMENTS, true);
        startScene(new Announcer(GalleryDetailScene.class).setArgs(args));

        // Older entries may have been written before tag enrichment was added.
        // Opportunistically fetch detail in the background and rewrite metadata so the
        // next open is also fully offline.
        Context context = getEHContext();
        if (context != null && (gi.tgList == null || gi.tgList.isEmpty())) {
            SmbMetadata.enrichLocalMetadataIfMissing(context, gi);
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
            GalleryInfo gi = mHelper != null ? mHelper.getDataAtEx(position) : null;
            return gi != null ? gi.gid : RecyclerView.NO_ID;
        }

        @Override
        public InventoryHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = getLayoutInflater2().inflate(R.layout.item_gallery_list, parent, false);
            return new InventoryHolder(v);
        }

        @Override
        public void onBindViewHolder(InventoryHolder holder, int position) {
            GalleryInfo gi = mHelper != null ? mHelper.getDataAtEx(position) : null;
            if (gi != null) {
                bind(holder, gi);
            }
        }

        @Override
        public int getItemCount() {
            return mHelper != null ? mHelper.size() : 0;
        }
    }

    private void bind(@NonNull InventoryHolder holder, @NonNull GalleryInfo gi) {
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

    /** One page's galleries plus the total page count, computed off the main thread. */
    private static final class PageResult {
        @NonNull final List<GalleryInfo> data;
        final int pages;

        PageResult(@NonNull List<GalleryInfo> data, int pages) {
            this.data = data;
            this.pages = pages;
        }
    }

    /** The full display ordering, computed once per refresh and sliced per page. */
    private static final class Ordering {
        @NonNull final List<SmbStorage.GalleryRef> refs;
        // null => date sort: each ref's metadata is read on demand for its page.
        // non-null => sort needed every folder's metadata to order, so it's all cached here.
        @Nullable final Map<String, GalleryInfo> infos;

        Ordering(@NonNull List<SmbStorage.GalleryRef> refs, @Nullable Map<String, GalleryInfo> infos) {
            this.refs = refs;
            this.infos = infos;
        }
    }

    private final class InventoryHelper extends GalleryInfoContentHelper {

        // Cached across page fetches so paging doesn't re-list the share every page. Rebuilt on
        // refresh. volatile because it's assigned/read from the load executor.
        @Nullable
        private volatile Ordering mOrdering;

        @Override
        protected void getPageData(int taskId, int type, int page) {
            // Date sort can order from the cheap listing alone; rebuild the ordering on a refresh or
            // when we have none yet (e.g. paging after the view was recreated).
            final boolean rebuild = type == TYPE_REFRESH || mOrdering == null;
            final SmbSortMode mode = SmbSortMode.fromOrdinal(Settings.getLocalInventorySort());
            Runnable task = () -> {
                final PageResult result;
                try {
                    // Issue #2644 requires a hard 7s cap — if the share can't be reached, surface the
                    // error state instead of hanging. jcifs's socket timeouts can't be bounded without
                    // rebuilding the global SingletonContext, so wrap the read in a Future.
                    ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "smb-inventory-page");
                        t.setDaemon(true);
                        return t;
                    });
                    Future<PageResult> fut = pool.submit(() -> loadPage(mode, page, rebuild));
                    try {
                        result = fut.get(7, TimeUnit.SECONDS);
                    } catch (TimeoutException te) {
                        fut.cancel(true);
                        throw new IOException(EhApplication.getInstance()
                                .getString(R.string.local_inventory_timeout));
                    } finally {
                        pool.shutdownNow();
                    }
                } catch (Throwable e) {
                    final Exception ex = e instanceof Exception ? (Exception) e : new Exception(e);
                    SimpleHandler.getInstance().post(() -> {
                        if (isCurrentTask(taskId)) {
                            onGetException(taskId, ex);
                        }
                    });
                    return;
                }
                SimpleHandler.getInstance().post(() -> {
                    if (!isCurrentTask(taskId)) {
                        return;
                    }
                    // Mark every gid on the page so cover/detail/reader reads route through SMB.
                    for (GalleryInfo gi : result.data) {
                        SmbStorage.markGidAsSmbTarget(gi.gid);
                    }
                    onGetPageData(taskId, result.pages, page + 1, result.data);
                });
            };
            if (mExecutor != null) {
                mExecutor.execute(task);
            } else {
                new Thread(task, "LocalInventoryLoader").start();
            }
        }

        @Override
        protected void getPageData(int taskId, int type, int page, String append) {
            getPageData(taskId, type, page);
        }

        @Override
        protected void getExPageData(int pageAction, int taskId, int page) {
            // Inventory paging is plain page-index based (no e-hentai prev/next hrefs), so this is the
            // same fetch as the normal path.
            getPageData(taskId, pageAction, page);
        }

        @NonNull
        private PageResult loadPage(@NonNull SmbSortMode mode, int page, boolean rebuild) {
            Ordering ordering = mOrdering;
            if (rebuild || ordering == null) {
                ordering = buildOrdering(mode);
                mOrdering = ordering;
            }
            List<SmbStorage.GalleryRef> refs = ordering.refs;
            int total = refs.size();
            int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
            List<GalleryInfo> data = new ArrayList<>();
            int from = page * PAGE_SIZE;
            int to = Math.min(from + PAGE_SIZE, total);
            for (int i = from; i < to; i++) {
                SmbStorage.GalleryRef ref = refs.get(i);
                GalleryInfo gi = ordering.infos != null
                        ? ordering.infos.get(ref.folderName)
                        : SmbStorage.readGalleryInfo(ref);
                if (gi != null) {
                    data.add(gi);
                }
            }
            return new PageResult(data, pages);
        }

        @NonNull
        private Ordering buildOrdering(@NonNull SmbSortMode mode) {
            if (mode == SmbSortMode.DOWNLOAD_DATE_DESC) {
                // Recently-downloaded order keys off the folder mtime the listing already carries, so
                // no metadata is read until a page needs it.
                List<SmbStorage.GalleryRef> refs = SmbStorage.listGalleryRefs();
                Collections.sort(refs, (a, b) -> Long.compare(b.folderMtime, a.folderMtime));
                return new Ordering(refs, null);
            }
            // Other sorts need fields that only live in metadata.json, so the whole share has to be
            // read to order it; cache it and serve pages from the cache.
            List<GalleryInfo> loaded = SmbStorage.loadInventory(mode);
            List<SmbStorage.GalleryRef> refs = new ArrayList<>(loaded.size());
            Map<String, GalleryInfo> infos = new HashMap<>();
            for (GalleryInfo gi : loaded) {
                String folderName = SmbPaths.buildGalleryFolderName(gi);
                refs.add(new SmbStorage.GalleryRef(folderName, 0L));
                infos.put(folderName, gi);
            }
            return new Ordering(refs, infos);
        }

        @Override
        protected Context getContext() {
            return LocalInventoryScene.this.getEHContext();
        }

        @Override
        protected void notifyDataSetChanged() {
            if (mAdapter != null) {
                mAdapter.notifyDataSetChanged();
            }
        }

        @Override
        protected void notifyItemRangeRemoved(int positionStart, int itemCount) {
            if (mAdapter != null) {
                mAdapter.notifyItemRangeRemoved(positionStart, itemCount);
            }
        }

        @Override
        protected void notifyItemRangeInserted(int positionStart, int itemCount) {
            if (mAdapter != null) {
                mAdapter.notifyItemRangeInserted(positionStart, itemCount);
            }
        }

        @Override
        protected boolean isDuplicate(GalleryInfo d1, GalleryInfo d2) {
            return d1.gid == d2.gid;
        }
    }

    private class GoToDialogHelper implements View.OnClickListener,
            DialogInterface.OnDismissListener {

        private final int mPages;
        private final int mCurrentPage;

        @Nullable
        private Slider mSlider;
        @Nullable
        private Dialog mDialog;

        private GoToDialogHelper(int pages, int currentPage) {
            mPages = pages;
            mCurrentPage = currentPage;
        }

        public void setDialog(@NonNull AlertDialog dialog) {
            mDialog = dialog;
            ((TextView) ViewUtils.$$(dialog, R.id.start)).setText(String.format(Locale.US, "%d", 1));
            ((TextView) ViewUtils.$$(dialog, R.id.end)).setText(String.format(Locale.US, "%d", mPages));
            mSlider = (Slider) ViewUtils.$$(dialog, R.id.slider);
            mSlider.setRange(1, mPages);
            mSlider.setProgress(mCurrentPage + 1);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(this);
            dialog.setOnDismissListener(this);
        }

        @Override
        public void onClick(View v) {
            if (mSlider == null || mHelper == null) {
                return;
            }
            int page = mSlider.getProgress() - 1;
            if (page >= 0 && page < mPages) {
                mHelper.goTo(page);
                if (mDialog != null) {
                    mDialog.dismiss();
                    mDialog = null;
                }
            }
        }

        @Override
        public void onDismiss(DialogInterface dialog) {
            mDialog = null;
            mSlider = null;
        }
    }
}
