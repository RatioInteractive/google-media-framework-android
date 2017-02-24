/**
 Copyright 2014 Google Inc. All rights reserved.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

/**
 * This file has been taken from the ExoPlayer demo project with minor modifications.
 * https://github.com/google/ExoPlayer/
 */
package com.google.android.libraries.mediaframework.exoplayerextensions;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaCodec;
import android.net.Uri;
import android.os.Handler;

import com.google.android.exoplayer.C;
import com.google.android.exoplayer.DefaultLoadControl;
import com.google.android.exoplayer.LoadControl;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.MediaCodecSelector;
import com.google.android.exoplayer.MediaCodecUtil.DecoderQueryException;
import com.google.android.exoplayer.MediaCodecVideoTrackRenderer;
import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.SampleSource;
import com.google.android.exoplayer.SingleSampleSource;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.audio.AudioCapabilities;
import com.google.android.exoplayer.chunk.VideoFormatSelectorUtil;
import com.google.android.exoplayer.hls.DefaultHlsTrackSelector;
import com.google.android.exoplayer.hls.HlsChunkSource;
import com.google.android.exoplayer.hls.HlsMasterPlaylist;
import com.google.android.exoplayer.hls.HlsPlaylist;
import com.google.android.exoplayer.hls.HlsPlaylistParser;
import com.google.android.exoplayer.hls.HlsSampleSource;
import com.google.android.exoplayer.hls.PtsTimestampAdjusterProvider;
import com.google.android.exoplayer.metadata.MetadataTrackRenderer;
import com.google.android.exoplayer.metadata.id3.Id3Frame;
import com.google.android.exoplayer.metadata.id3.Id3Parser;
import com.google.android.exoplayer.text.TextTrackRenderer;
import com.google.android.exoplayer.text.eia608.Eia608TrackRenderer;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DefaultAllocator;
import com.google.android.exoplayer.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer.upstream.DefaultUriDataSource;
import com.google.android.exoplayer.util.ManifestFetcher;
import com.google.android.exoplayer.util.ManifestFetcher.ManifestCallback;
import com.google.android.exoplayer.util.MimeTypes;
import com.google.android.libraries.mediaframework.exoplayerextensions.ExoplayerWrapper.RendererBuilder;

import java.io.IOException;
import java.util.List;

/**
 * A {@link RendererBuilder} for HLS.
 */
public class HlsRendererBuilder implements RendererBuilder {

  private static final int BUFFER_SEGMENT_SIZE = 64 * 1024;
  private static final int MAIN_BUFFER_SEGMENTS = 256;
  private static final int TEXT_BUFFER_SEGMENTS = 2;

  private final Context context;
  private final String userAgent;
  private final String url;
  private final String webVTTSidecarUrl;

  private ExoplayerWrapper player;

  private AsyncRendererBuilder currentAsyncBuilder;

  public HlsRendererBuilder(Context context, String userAgent, String url, String webVTTSidecarUrl) {
    this.context = context;
    this.userAgent = userAgent;
    this.url = url;
    this.webVTTSidecarUrl = webVTTSidecarUrl;
  }

  @Override
  public void buildRenderers(ExoplayerWrapper player) {
    this.player = player;
    currentAsyncBuilder = new AsyncRendererBuilder(context, userAgent, url, webVTTSidecarUrl, player);
    currentAsyncBuilder.init();
  }

  @Override
  public void cancel() {
    if (currentAsyncBuilder != null) {
      currentAsyncBuilder.cancel();
      currentAsyncBuilder = null;
    }
  }

  private static final class AsyncRendererBuilder implements ManifestCallback<HlsPlaylist> {

    private final Context context;
    private final String userAgent;
    private final String url;
    private final String webVTTSidecarUrl;
    private final ExoplayerWrapper player;
    private final ManifestFetcher<HlsPlaylist> playlistFetcher;

    private boolean canceled;

    public AsyncRendererBuilder(Context context, String userAgent, String url, String webVTTSidecarUrl, ExoplayerWrapper player) {
      this.context = context;
      this.userAgent = userAgent;
      this.url = url;
      this.webVTTSidecarUrl = webVTTSidecarUrl;
      this.player = player;
      HlsPlaylistParser parser = new HlsPlaylistParser();
      playlistFetcher = new ManifestFetcher<>(url, new DefaultUriDataSource(context, userAgent),
              parser);
    }

    public void init() {
      playlistFetcher.singleLoad(player.getMainHandler().getLooper(), this);
    }

    public void cancel() {
      canceled = true;
    }

    @Override
    public void onSingleManifestError(IOException e) {
      if (canceled) {
        return;
      }

      player.onRenderersError(e);
    }

    @Override
    public void onSingleManifest(HlsPlaylist manifest) {
      if (canceled) {
        return;
      }

      Handler mainHandler = player.getMainHandler();
      LoadControl loadControl = new DefaultLoadControl(new DefaultAllocator(BUFFER_SEGMENT_SIZE));
      DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();

      int[] variantIndices = null;
      if (manifest instanceof HlsMasterPlaylist) {
        HlsMasterPlaylist masterPlaylist = (HlsMasterPlaylist) manifest;
        try {
          variantIndices = VideoFormatSelectorUtil.selectVideoFormatsForDefaultDisplay(
                  context, masterPlaylist.variants, null, false);
        } catch (DecoderQueryException e) {
          player.onRenderersError(e);
          return;
        }
        if (variantIndices.length == 0) {
          player.onRenderersError(new IllegalStateException("No variants selected."));
          return;
        }
      }

      PtsTimestampAdjusterProvider timestampAdjusterProvider = new PtsTimestampAdjusterProvider();
      DataSource dataSource = new DefaultUriDataSource(context, bandwidthMeter, userAgent);
      HlsChunkSource chunkSource = new HlsChunkSource(true /* isMaster */, dataSource, url,
              manifest, DefaultHlsTrackSelector.newDefaultInstance(context), bandwidthMeter,
              timestampAdjusterProvider, HlsChunkSource.ADAPTIVE_MODE_SPLICE);
      HlsSampleSource sampleSource = new HlsSampleSource(chunkSource, loadControl,
              MAIN_BUFFER_SEGMENTS * BUFFER_SEGMENT_SIZE, mainHandler, player, ExoplayerWrapper.TYPE_VIDEO);
      MediaCodecVideoTrackRenderer videoRenderer = new MediaCodecVideoTrackRenderer(context,
              sampleSource, MediaCodecSelector.DEFAULT, MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT, 5000, mainHandler, player, 50);
      MediaCodecAudioTrackRenderer audioRenderer = new MediaCodecAudioTrackRenderer(sampleSource,
              MediaCodecSelector.DEFAULT, null, true, player.getMainHandler(), player,
              AudioCapabilities.getCapabilities(context), AudioManager.STREAM_MUSIC);

      MetadataTrackRenderer<List<Id3Frame>> id3Renderer = new MetadataTrackRenderer<>(
              sampleSource, new Id3Parser(), player, mainHandler.getLooper());

      // Priority of subtitles:
      // 1. Embedded WebVTT
      // 2. Sidecar WebVTT
      // 3. Embedded CEA-608

      // Build the text renderer
      boolean preferWebvtt = false;
      if (manifest instanceof HlsMasterPlaylist) {
        preferWebvtt = !((HlsMasterPlaylist) manifest).subtitles.isEmpty();
      }
      if (!preferWebvtt) {
        preferWebvtt = webVTTSidecarUrl != null;
      }

      TrackRenderer textRenderer;
      if (preferWebvtt) {

        DataSource textDataSource = new DefaultUriDataSource(context, bandwidthMeter, userAgent);
        HlsChunkSource textChunkSource = new HlsChunkSource(false /* isMaster */, textDataSource,
                url, manifest, DefaultHlsTrackSelector.newSubtitleInstance(), bandwidthMeter,
                timestampAdjusterProvider, HlsChunkSource.ADAPTIVE_MODE_SPLICE);

        SampleSource textSampleSource;

        // Embedded WebVTT
        if (webVTTSidecarUrl == null) {
          textSampleSource = new HlsSampleSource(textChunkSource, loadControl,
                  TEXT_BUFFER_SEGMENTS * BUFFER_SEGMENT_SIZE, mainHandler, player, ExoplayerWrapper.TYPE_TEXT);
        }
        // Sidecar WebVTT
        else {
          MediaFormat mediaFormat = MediaFormat.createTextFormat("id", MimeTypes.TEXT_VTT, MediaFormat.NO_VALUE, C.MATCH_LONGEST_US, "en");
          textSampleSource = new SingleSampleSource(Uri.parse(webVTTSidecarUrl), textDataSource, mediaFormat);
        }

        textRenderer = new TextTrackRenderer(textSampleSource, player, mainHandler.getLooper());

      } else {
        textRenderer = new Eia608TrackRenderer(sampleSource, player, mainHandler.getLooper());
      }

      TrackRenderer[] renderers = new TrackRenderer[ExoplayerWrapper.RENDERER_COUNT];
      renderers[ExoplayerWrapper.TYPE_VIDEO] = videoRenderer;
      renderers[ExoplayerWrapper.TYPE_AUDIO] = audioRenderer;
      renderers[ExoplayerWrapper.TYPE_METADATA] = id3Renderer;
      renderers[ExoplayerWrapper.TYPE_TEXT] = textRenderer;
      player.onRenderers(renderers, bandwidthMeter);
    }
  }
}