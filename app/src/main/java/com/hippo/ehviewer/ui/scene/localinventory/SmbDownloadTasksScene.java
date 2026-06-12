/*
 * Copyright 2024 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.smb.SmbDirectDownloader;
import com.hippo.ehviewer.smb.SmbDirectDownloader.TaskSnapshot;
import com.hippo.ehviewer.ui.scene.ToolbarScene;
import com.hippo.lib.yorozuya.ViewUtils;
import com.hippo.util.DrawableManager;
import com.hippo.view.ViewTransition;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists every in-flight SMB download task (active + queued + paused) with controls to
 * pause / resume / cancel each one. Reuses {@link SmbDirectDownloader}'s snapshot +
 * observer surface so the view auto-refreshes as progress, queue, and pause-state change.
 *
 * <p>UI styling mirrors the rest of the app: per-item {@code CardView.Reactive} cards with
 * {@code CardTitle} / {@code CardMessage} typography and {@code ButtonInCard}-styled
 * borderless actions divided by the standard divider, plus the sad-pandroid empty state
 * used by {@code ContentLayout}-based scenes.
 */
public class SmbDownloadTasksScene extends ToolbarScene
        implements SmbDirectDownloader.TaskObserver {

    @Nullable
    private RecyclerView mRecyclerView;
    @Nullable
    private TextView mTip;
    @Nullable
    private ViewTransition mViewTransition;
    @Nullable
    private Adapter mAdapter;
    private final List<TaskSnapshot> mItems = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView3(LayoutInflater inflater,
                              @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.scene_smb_tasks, container, false);
        View content = ViewUtils.$$(view, R.id.content);
        mRecyclerView = (RecyclerView) ViewUtils.$$(content, R.id.recycler_view);
        mTip = (TextView) ViewUtils.$$(view, R.id.tip);
        mViewTransition = new ViewTransition(content, mTip);

        Context context = getContext();
        if (context != null) {
            Drawable sadDrawable = DrawableManager.getVectorDrawable(context, R.drawable.big_sad_pandroid);
            if (sadDrawable != null) {
                sadDrawable.setBounds(0, 0, sadDrawable.getIntrinsicWidth(), sadDrawable.getIntrinsicHeight());
                mTip.setCompoundDrawables(null, sadDrawable, null, null);
            }
        }

        mAdapter = new Adapter();
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecyclerView.setAdapter(mAdapter);
        return view;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setTitle(R.string.smb_download_tasks);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
    }

    @Override
    public void onResume() {
        super.onResume();
        SmbDirectDownloader.getInstance().addTaskObserver(this);
        refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        SmbDirectDownloader.getInstance().removeTaskObserver(this);
    }

    @Override
    public void onNavigationClick(View view) {
        onBackPressed();
    }

    @Override
    public void onTasksChanged() {
        refresh();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mRecyclerView = null;
        mTip = null;
        mViewTransition = null;
        mAdapter = null;
    }

    private void refresh() {
        mItems.clear();
        mItems.addAll(SmbDirectDownloader.getInstance().snapshotTasks());
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
        if (mViewTransition != null) {
            mViewTransition.showView(mItems.isEmpty() ? 1 : 0, true);
        }
    }

    private final class Adapter extends RecyclerView.Adapter<TaskHolder> {
        @NonNull
        @Override
        public TaskHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_smb_task, parent, false);
            return new TaskHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull TaskHolder holder, int position) {
            holder.bind(mItems.get(position));
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }
    }

    private final class TaskHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView state;
        final ProgressBar progress;
        final TextView pauseResume;
        final TextView cancel;

        TaskHolder(View v) {
            super(v);
            title = (TextView) v.findViewById(R.id.title);
            state = (TextView) v.findViewById(R.id.state);
            progress = (ProgressBar) v.findViewById(R.id.progress);
            pauseResume = (TextView) v.findViewById(R.id.pause_resume);
            cancel = (TextView) v.findViewById(R.id.cancel);
        }

        void bind(@NonNull TaskSnapshot t) {
            title.setText(t.title != null ? t.title : ("gid " + t.gid));
            int stateRes;
            switch (t.state) {
                case ACTIVE: stateRes = R.string.smb_task_state_active; break;
                case QUEUED: stateRes = R.string.smb_task_state_queued; break;
                case PAUSED:
                default:     stateRes = R.string.smb_task_state_paused; break;
            }
            String stateText = itemView.getResources().getString(stateRes);
            if (t.total > 0) {
                stateText = stateText + "  " + t.finished + "/" + t.total;
                progress.setMax(t.total);
                progress.setProgress(t.finished);
                progress.setIndeterminate(false);
            } else {
                progress.setIndeterminate(t.state == TaskSnapshot.State.ACTIVE);
                progress.setProgress(0);
            }
            state.setText(stateText);

            if (t.state == TaskSnapshot.State.PAUSED) {
                pauseResume.setText(R.string.smb_task_resume);
                pauseResume.setOnClickListener(v -> SmbDirectDownloader.getInstance().resume(t.gid));
            } else {
                pauseResume.setText(R.string.smb_task_pause);
                pauseResume.setOnClickListener(v -> SmbDirectDownloader.getInstance().pause(t.gid));
            }
            cancel.setOnClickListener(v -> new AlertDialog.Builder(itemView.getContext())
                    .setTitle(R.string.smb_task_cancel)
                    .setMessage(R.string.smb_task_cancel_confirm)
                    .setPositiveButton(android.R.string.ok,
                            (d, w) -> SmbDirectDownloader.getInstance().cancel(t.gid))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
        }
    }
}
