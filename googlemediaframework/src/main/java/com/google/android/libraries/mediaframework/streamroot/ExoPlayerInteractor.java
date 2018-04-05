package com.google.android.libraries.mediaframework.streamroot;


import com.google.android.exoplayer.ExoPlayer;

import java.util.ArrayList;
import java.util.List;

import io.streamroot.dna.core.StreamrootDNA;

public class ExoPlayerInteractor implements StreamrootDNA.Interactor {
    private ExoPlayer player;

    public ExoPlayerInteractor(ExoPlayer player) {
        this.player = player;
        this.mTimeRanges = new ArrayList<>();
    }

    private List<TimeRange> mTimeRanges;

    @Override
     public List<TimeRange> loadedTimeRanges() {
        return mTimeRanges;
    }

    @Override
    public long playbackTime() {
        return player.getCurrentPosition();
    }
}