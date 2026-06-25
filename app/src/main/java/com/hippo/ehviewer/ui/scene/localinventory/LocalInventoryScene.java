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

import androidx.annotation.NonNull;
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
import com.hippo.ehviewer.smb.SmbMetadata;
import com.hippo.ehviewer.smb.SmbPaths;
import com.hippo.ehviewer.smb.SmbSortMode;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    // The gallery folders on the share, in display order. For the default date sort these are
    // listed cheaply (folder name + mtime) and each folder's metadata.json is read lazily as its row
    // scrolls into view; for sorts that need metadata to order, every folder is read up front.
    private final List<SmbStorage.GalleryRef> mRefs = new ArrayList<>();
    // folderName -> loaded metadata, populated lazily on bind. A concurrent map keeps it safe if a
    // late row-load post lands during teardown; otherwise it's only touched on the main thread.
    private final Map<String, GalleryInfo> mInfoCache = new ConcurrentHashMap<>();
    // Folder names with an in-flight metadata read, so a row bound repeatedly while its read is
    // outstanding doesn't enqueue duplicate SMB reads.
    private final Set<String> mLoadingRefs = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean mLoading;
    // First visible adapter position, captured when the view is torn down (e.g. on the way into a
    // gallery detail) so it can be restored when the scene comes back, instead of snapping to the top.
    private int mSavedFirstVisible = 0;
    @Nullable
    private ExecutorService mExecutor;
    // Bounded pool for the lazy per-row metadata reads, so scrolling a big share reads a few folders
    // at a time instead of flooding the share or starving the shared app pool.
    @Nullable
    private ExecutorService mRowExecutor;

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
        mRowExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "smb-inventory-row");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mRowExecutor != null) {
            mRowExecutor.shutdownNow();
            mRowExecutor = null;
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

        if (!mRefs.isEmpty()) {
            // The scene fragment survived (e.g. we're coming back from a gallery detail) and still
            // holds a listed inventory (plus whatever metadata was already read). Reuse it and restore
            // the previous scroll position rather than re-scanning the whole share and jumping back to
            // the top — matches how the normal gallery list keeps its place on return.
            mAdapter.notifyDataSetChanged();
            if (mViewTransition != null) {
                mViewTransition.showView(0, false);
            }
            if (mSavedFirstVisible > 0) {
                mRecyclerView.scrollToPosition(mSavedFirstVisible);
            }
        } else {
            showLoadingState();
            reload();
        }
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
        // Deliberately no reload() here. Re-scanning the whole share on every resume (which fires
        // when returning from a gallery detail) wastes a full SMB metadata sweep and discards the
        // user's scroll position. New downloads surface via the refresh menu / sort, like the
        // normal gallery list.
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
            // Remember where the user was so onCreateView3 can restore it when the scene returns.
            RecyclerView.LayoutManager lm = mRecyclerView.getLayoutManager();
            if (lm instanceof StaggeredGridLayoutManager) {
                int[] firstVisible = ((StaggeredGridLayoutManager) lm).findFirstVisibleItemPositions(null);
                if (firstVisible != null && firstVisible.length > 0 && firstVisible[0] >= 0) {
                    mSavedFirstVisible = firstVisible[0];
                }
            }
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
        // A reload is a fresh sweep (first load, refresh, or sort change); start from the top so a
        // later view recreation doesn't restore a position that may no longer exist.
        mSavedFirstVisible = 0;
        final SmbSortMode mode = SmbSortMode.fromOrdinal(Settings.getLocalInventorySort());
        // Snapshot the application context up front. The Runnable below runs on the worker
        // pool and the SimpleHandler.post lambdas run on the main thread; both can fire
        // after onDestroyView has detached the fragment. Calling Fragment.getString from
        // there would throw IllegalStateException. The app context lives for the process,
        // so it's safe regardless of scene lifecycle.
        Context ctxSnapshot = getEHContext();
        final Context appContext = ctxSnapshot != null
                ? ctxSnapshot.getApplicationContext()
                : EhApplication.getInstance();
        Runnable task = () -> {
            final OrderedRefs result;
            try {
                // Issue #2644 requires a 7s timeout — if the share can't be reached by then,
                // surface the sad-pandroid error state instead of hanging the scene indefinitely.
                // We can't bound jcifs's internal socket timeouts without rebuilding the global
                // SingletonContext, so we wrap the actual load in a Future with a hard cap. For the
                // default date sort the wrapped work is just one cheap share listing; other sorts
                // still read every folder here because they can't be ordered without the metadata.
                java.util.concurrent.ExecutorService pool =
                        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                            Thread t = new Thread(r, "smb-inventory-load");
                            t.setDaemon(true);
                            return t;
                        });
                Future<OrderedRefs> fut = pool.submit(() -> loadOrderedRefs(mode));
                try {
                    result = fut.get(7, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    fut.cancel(true);
                    throw new java.io.IOException(appContext.getString(R.string.local_inventory_timeout));
                } finally {
                    pool.shutdownNow();
                }
            } catch (Throwable e) {
                SimpleHandler.getInstance().post(() -> {
                    mLoading = false;
                    if (mTip != null) {
                        mTip.setText(appContext.getString(R.string.local_inventory_error, e.getMessage()));
                    }
                    if (mViewTransition != null) {
                        mViewTransition.showView(1, true);
                    }
                });
                return;
            }
            SimpleHandler.getInstance().post(() -> {
                mLoading = false;
                mRefs.clear();
                mInfoCache.clear();
                mLoadingRefs.clear();
                if (result != null) {
                    mRefs.addAll(result.refs);
                    if (result.infos != null) {
                        // A metadata-ordered sort already read every folder; cache it so rows bind
                        // without a second read, and pre-mark each gid so any read path (cover load,
                        // detail navigation, reader launch) routes through SmbStorage.
                        mInfoCache.putAll(result.infos);
                        for (GalleryInfo gi : result.infos.values()) {
                            SmbStorage.markGidAsSmbTarget(gi.gid);
                        }
                    }
                }
                if (mAdapter != null) {
                    mAdapter.notifyDataSetChanged();
                }
                if (mViewTransition != null) {
                    if (mRefs.isEmpty()) {
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

    /** Ordered gallery folders, plus their metadata when the sort required reading it all. */
    private static final class OrderedRefs {
        @NonNull final List<SmbStorage.GalleryRef> refs;
        // null => the order needed no metadata (date sort); rows read it lazily on bind.
        @Nullable final Map<String, GalleryInfo> infos;

        OrderedRefs(@NonNull List<SmbStorage.GalleryRef> refs, @Nullable Map<String, GalleryInfo> infos) {
            this.refs = refs;
            this.infos = infos;
        }
    }

    /**
     * Produces the display-ordered folder list. The default "recently downloaded" order keys off the
     * folder mtime that the share listing already carries, so it reads no metadata — rows fetch it
     * lazily as they scroll in. Every other sort needs fields that only live inside
     * {@code metadata.json}, so those read every folder up front (via {@link SmbStorage#loadInventory})
     * and hand back a fully-populated cache.
     */
    @NonNull
    private static OrderedRefs loadOrderedRefs(@NonNull SmbSortMode mode) {
        if (mode == SmbSortMode.DOWNLOAD_DATE_DESC) {
            List<SmbStorage.GalleryRef> refs = SmbStorage.listGalleryRefs();
            Collections.sort(refs, (a, b) -> Long.compare(b.folderMtime, a.folderMtime));
            return new OrderedRefs(refs, null);
        }
        List<GalleryInfo> loaded = SmbStorage.loadInventory(mode);
        List<SmbStorage.GalleryRef> refs = new ArrayList<>(loaded.size());
        Map<String, GalleryInfo> infos = new HashMap<>();
        for (GalleryInfo gi : loaded) {
            String folderName = SmbPaths.buildGalleryFolderName(gi);
            refs.add(new SmbStorage.GalleryRef(folderName, 0L));
            infos.put(folderName, gi);
        }
        return new OrderedRefs(refs, infos);
    }

    /**
     * Reads one folder's metadata off the main thread (lazy date-sort path) and, when it lands,
     * caches it and refreshes that row. De-duplicates concurrent binds of the same folder.
     */
    private void enqueueRowLoad(@NonNull SmbStorage.GalleryRef ref) {
        final String folderName = ref.folderName;
        if (!mLoadingRefs.add(folderName)) {
            return; // already being read
        }
        ExecutorService pool = mRowExecutor;
        if (pool == null || pool.isShutdown()) {
            mLoadingRefs.remove(folderName);
            return;
        }
        pool.execute(() -> {
            final GalleryInfo info = SmbStorage.readGalleryInfo(ref);
            SimpleHandler.getInstance().post(() -> {
                mLoadingRefs.remove(folderName);
                if (info != null) {
                    mInfoCache.put(folderName, info);
                    SmbStorage.markGidAsSmbTarget(info.gid);
                    if (mAdapter != null) {
                        int pos = mRefs.indexOf(ref);
                        if (pos >= 0) {
                            mAdapter.notifyItemChanged(pos);
                        }
                    }
                } else {
                    // Not a readable gallery folder (no/unparseable metadata.json) — drop the row so
                    // it doesn't leave a blank cell. Mirrors loadInventory, which skips such folders.
                    int pos = mRefs.indexOf(ref);
                    if (pos >= 0) {
                        mRefs.remove(pos);
                        if (mAdapter != null) {
                            mAdapter.notifyItemRemoved(pos);
                        }
                    }
                }
            });
        });
    }

    /** Loaded metadata for a row, or null when it isn't read yet. */
    @Nullable
    private GalleryInfo infoAt(int position) {
        if (position < 0 || position >= mRefs.size()) {
            return null;
        }
        return mInfoCache.get(mRefs.get(position).folderName);
    }

    @Override
    public boolean onItemClick(EasyRecyclerView parent, View view, int position, long id) {
        GalleryInfo gi = infoAt(position);
        if (gi == null) {
            // Row still loading its metadata (or broken) — nothing to open yet.
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
            if (position < 0 || position >= mRefs.size()) return RecyclerView.NO_ID;
            // Key off the folder name, not the gid: the gid isn't known until the row's metadata is
            // read, and a stable id that survives the placeholder -> loaded transition lets
            // notifyItemChanged refresh the row in place instead of animating a remove/add.
            return mRefs.get(position).folderName.hashCode();
        }

        @Override
        public InventoryHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = getLayoutInflater2().inflate(R.layout.item_gallery_list, parent, false);
            return new InventoryHolder(v);
        }

        @Override
        public void onBindViewHolder(InventoryHolder holder, int position) {
            SmbStorage.GalleryRef ref = mRefs.get(position);
            GalleryInfo gi = mInfoCache.get(ref.folderName);
            if (gi != null) {
                bindInfo(holder, gi);
            } else {
                // Not read yet: show a blank cell and kick off the lazy read; the row refreshes
                // itself (or drops out) when the metadata lands.
                bindPlaceholder(holder);
                enqueueRowLoad(ref);
            }
        }

        @Override
        public int getItemCount() {
            return mRefs.size();
        }
    }

    private void bindInfo(@NonNull InventoryHolder holder, @NonNull GalleryInfo gi) {
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

    private void bindPlaceholder(@NonNull InventoryHolder holder) {
        // Clear every field a recycled holder might still be showing so a not-yet-loaded row doesn't
        // flash the previous gallery's cover/title.
        holder.thumb.unload();
        holder.thumb.setOnClickListener(null);
        holder.title.setText(null);
        holder.uploader.setText(null);
        holder.rating.setRating(0f);
        holder.category.setText(null);
        holder.category.setBackgroundColor(Color.TRANSPARENT);
        holder.posted.setText(null);
        holder.simpleLanguage.setText(null);
        holder.pages.setText(null);
    }
}
