import java.lang.invoke.MethodHandles;
import java.io.File;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.chart.XYChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.collections.ObservableList;
import javafx.collections.FXCollections;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.HelpFormatter;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.util.MathArrays;

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;
import jp.ac.kyoto_u.kuis.le4music.Player;
import jp.ac.kyoto_u.kuis.le4music.Recorder;
import jp.ac.kyoto_u.kuis.le4music.LineChartWithSpectrogram;
import static jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils.verbose;

import java.io.IOException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.LineUnavailableException;
import org.apache.commons.cli.ParseException;

public final class RecordMonitorSpectrogramTest extends Application {

  private static final Options options = new Options();
  private static final String helpMessage =
    MethodHandles.lookup().lookupClass().getName() + " [OPTIONS] <WAVFILE>";

  static {
    /* コマンドラインオプション定義 */
    options.addOption("h", "help", false, "display this help and exit");
    options.addOption("v", "verbose", false, "Verbose output");
    options.addOption("m", "mixer", true,
                      "Index of the Mixer object that supplies a SourceDataLine object. " +
                      "To check the proper index, use CheckAudioSystem");
    options.addOption("l", "loop", false, "Loop playback");
    options.addOption("f", "frame", true,
                      "Frame duration [seconds] " +
                      "(Default: " + Le4MusicUtils.frameDuration + ")");
    options.addOption("i", "interval", true,
                      "Frame notification interval [seconds] " +
                      "(Default: " + Le4MusicUtils.frameInterval + ")");
    options.addOption("b", "buffer", true,
                      "Duration of line buffer [seconds]");
    options.addOption("d", "duration", true,
                      "Duration of spectrogram [seconds]");
    options.addOption(null, "amp-lo", true,
                      "Lower bound of amplitude [dB] (Default: " +
                      Le4MusicUtils.spectrumAmplitudeLowerBound + ")");
    options.addOption(null, "amp-up", true,
                      "Upper bound of amplitude [dB] (Default: " +
                      Le4MusicUtils.spectrumAmplitudeUpperBound + ")");
    options.addOption(null, "freq-lo", true,
                      "Lower bound of frequency [Hz] (Default: 0.0)");
    options.addOption(null, "freq-up", true,
                      "Upper bound of frequency [Hz] (Default: Nyquist)");
  }

  @Override /* Application */
  public final void start(final Stage primaryStage)
  throws IOException,
         UnsupportedAudioFileException,
         LineUnavailableException,
         ParseException {
    /* コマンドライン引数処理 */
    final String[] args = getParameters().getRaw().toArray(new String[0]);
    final CommandLine cmd = new DefaultParser().parse(options, args);
    if (cmd.hasOption("help")) {
      new HelpFormatter().printHelp(helpMessage, options);
      Platform.exit();
      return;
    }
    verbose = cmd.hasOption("verbose");

    final String[] pargs = cmd.getArgs();
    if (pargs.length < 1) {
      System.out.println("WAVFILE is not given.");
      new HelpFormatter().printHelp(helpMessage, options);
      Platform.exit();
      return;
    }
    final File wavFile = new File(pargs[0]);

    final double frameDuration =
      Optional.ofNullable(cmd.getOptionValue("frame"))
        .map(Double::parseDouble)
        .orElse(Le4MusicUtils.frameDuration);
    
    final double duration =
      Optional.ofNullable(cmd.getOptionValue("duration"))
        .map(Double::parseDouble)
        .orElse(Le4MusicUtils.spectrogramDuration);
    final double interval =
      Optional.ofNullable(cmd.getOptionValue("interval"))
        .map(Double::parseDouble)
        .orElse(Le4MusicUtils.frameInterval);
    
    // ステージを追加
    Stage secondaryStage = new Stage();
    Stage micInputStage = new Stage();
    
    // ウィンドウ位置調整
    primaryStage.setX(100);
    primaryStage.setY(100);
    secondaryStage.setX(1000);
    secondaryStage.setY(100);
    micInputStage.setX(100);
    micInputStage.setY(1000);

    /* Player を作成 */
    final Player.Builder builder = Player.builder(wavFile);
    Optional.ofNullable(cmd.getOptionValue("mixer"))
      .map(Integer::parseInt)
      .map(index -> AudioSystem.getMixerInfo()[index])
      .ifPresent(builder::mixer);
    if (cmd.hasOption("loop"))
      builder.loop();
    Optional.ofNullable(cmd.getOptionValue("buffer"))
      .map(Double::parseDouble)
      .ifPresent(builder::bufferDuration);
    Optional.ofNullable(cmd.getOptionValue("frame"))
      .map(Double::parseDouble)
      .ifPresent(builder::frameDuration);
    builder.interval(interval);
    builder.daemon();
    final Player player = builder.build();
    
    // Recorder を作成
    int mixerIndex = 0;
    final Recorder.Builder builder2 = Recorder.builder();
    Optional.ofNullable(cmd.getOptionValue("mixer"))
      .map(Integer::parseInt)
      .map(index -> AudioSystem.getMixerInfo()[index])
      .ifPresent(builder2::mixer);
    Optional.ofNullable(cmd.getOptionValue("frame"))
      .map(Double::parseDouble)
      .ifPresent(builder2::frameDuration);
    builder2.interval(interval);
    builder2.daemon();
    final Recorder recorder = builder2.build();
    
    ////////////////////////////////////////////////////////////////////////////
    // スペクトログラム作成
    ////////////////////////////////////////////////////////////////////////////

    /* データ処理スレッド */
    final ExecutorService executor = Executors.newSingleThreadExecutor();

    /* 窓関数とFFTのサンプル数 */
    final int fftSize = 1 << Le4MusicUtils.nextPow2(player.getFrameSize());
    final int fftSize2 = (fftSize >> 1) + 1;

    /* 窓関数を求め，それを正規化する */
    final double[] window =
      MathArrays.normalizeArray(Le4MusicUtils.hanning(player.getFrameSize()), 1.0);

    /* 各フーリエ変換係数に対応する周波数 */
    final double[] freqs =
      IntStream.range(0, fftSize2)
               .mapToDouble(i -> i * player.getSampleRate() / fftSize)
               .toArray();

    /* フレーム数 */
    final int frames = (int)Math.round(duration / interval);

    /* 軸を作成 */
    final NumberAxis xAxis = new NumberAxis(
      /* axisLabel  = */ "Time (seconds)",
      /* lowerBound = */ -duration,
      /* upperBound = */ 0,
      /* tickUnit   = */ Le4MusicUtils.autoTickUnit(duration)
    );
    xAxis.setAnimated(false);

    final double freqLowerBound =
      Optional.ofNullable(cmd.getOptionValue("freq-lo"))
        .map(Double::parseDouble)
        .orElse(0.0);
    if (freqLowerBound < 0.0)
      throw new IllegalArgumentException(
        "freq-lo must be non-negative: " + freqLowerBound
      );
    final double freqUpperBound =
      Optional.ofNullable(cmd.getOptionValue("freq-up"))
        .map(Double::parseDouble)
        .orElse(player.getNyquist());
    if (freqUpperBound <= freqLowerBound)
      throw new IllegalArgumentException(
        "freq-up must be larger than freq-lo: " +
        "freq-lo = " + freqLowerBound + ", freq-up = " + freqUpperBound
      );
    final NumberAxis yAxis = new NumberAxis(
      /* axisLabel  = */ "Frequency (Hz)",
      /* lowerBound = */ freqLowerBound,
      /* upperBound = */ freqUpperBound,
      /* tickUnit   = */ Le4MusicUtils.autoTickUnit(freqUpperBound - freqLowerBound)
    );
    yAxis.setAnimated(false);

    /* スペクトログラム表示chart */
    final LineChartWithSpectrogram<Number, Number> chart_sgram =
      new LineChartWithSpectrogram<>(xAxis, yAxis);
    chart_sgram.setParameters(frames, fftSize2, player.getNyquist());
    chart_sgram.setTitle("Spectrogram");

    /* グラフ描画 */
    final Scene scene = new Scene(chart_sgram, 800, 600);
    scene.getStylesheets().add("le4music.css");
    primaryStage.setScene(scene);
    primaryStage.setTitle(getClass().getName());
    /* ウインドウを閉じたときに他スレッドも停止させる */
    primaryStage.setOnCloseRequest(req -> executor.shutdown());
    primaryStage.show();
    Platform.setImplicitExit(true);
    
    ////////////////////////////////////////////////////////////////////////////
    // 入力音響信号から Waveform 作成
    ////////////////////////////////////////////////////////////////////////////

    /* データ系列を作成 */
    final ObservableList<XYChart.Data<Number, Number>> data =
      IntStream.range(0, player.getFrameSize())
        .mapToObj(i -> new XYChart.Data<Number, Number>(i / player.getSampleRate(), 0.0))
        .collect(Collectors.toCollection(FXCollections::observableArrayList));

    /* データ系列に名前をつける */
    final XYChart.Series<Number, Number> series =
      new XYChart.Series<>("Waveform", data);

    /* 軸を作成 */
    final NumberAxis xAxis2 = new NumberAxis(
      /* axisLabel  = */ "Time (seconds)",
      /* lowerBound = */ -frameDuration,
      /* upperBound = */ 0.0,
      /* tickUnit   = */ Le4MusicUtils.autoTickUnit(frameDuration)
    );
    final double ampBounds =
      Optional.ofNullable(cmd.getOptionValue("amp-bounds"))
        .map(Double::parseDouble)
        .orElse(Le4MusicUtils.waveformAmplitudeBounds);
    final NumberAxis yAxis2 = new NumberAxis(
      /* axisLabel  = */ "Amplitude",
      /* lowerBound = */ -ampBounds,
      /* upperBound = */ +ampBounds,
      /* tickUnit   = */ Le4MusicUtils.autoTickUnit(ampBounds * 2.0)
    );

    /* チャートを作成 */
    final LineChart<Number, Number> chart = new LineChart<>(xAxis2, yAxis2);
    chart.setTitle("Waveform");
    chart.setCreateSymbols(false);
    chart.setLegendVisible(false);
    chart.setAnimated(false);
    chart.getData().add(series);

    /* 描画ウインドウ作成 */
    final Scene scene2  = new Scene(chart, 800, 600);
    scene2.getStylesheets().add("le4music.css");
    secondaryStage.setScene(scene2);
    secondaryStage.setTitle(getClass().getName());
    secondaryStage.setOnCloseRequest(req -> executor.shutdown());
    secondaryStage.show();
    
    ////////////////////////////////////////////////////////////////////////////
    // マイク入力から 基本周波数を表示するLineChart 作成
    ////////////////////////////////////////////////////////////////////////////
    
    final ExecutorService executor2 = Executors.newSingleThreadExecutor();
    
    /* 窓関数とFFTのサンプル数 */
    final int fftSize_mic = 1 << Le4MusicUtils.nextPow2(recorder.getFrameSize());
    final int fftSize_mic2 = (fftSize_mic >> 1) + 1;

    /* 窓関数を求め，それを正規化する */
    final double[] window_mic =
      MathArrays.normalizeArray(Le4MusicUtils.hanning(recorder.getFrameSize()), 1.0);

    /* 各フーリエ変換係数に対応する周波数 */
    final double[] freqs_mic =
      IntStream.range(0, fftSize_mic2)
               .mapToDouble(i -> i * recorder.getSampleRate() / fftSize_mic)
               .toArray();

    /* フレーム数 */
    final int frames_mic = (int)Math.round(duration / interval);
    
    // データ系列を作成
    final ObservableList<XYChart.Data<Number, Number>> data_mic =
      IntStream.range(0, 500)
        .mapToObj(i -> new XYChart.Data<Number, Number>(i / recorder.getSampleRate(), 0.0))
        .collect(Collectors.toCollection(FXCollections::observableArrayList));
    
    // データ系列に名前をつける
    final XYChart.Series<Number, Number> series_mic =
      new XYChart.Series<>("Waveform from mic", data_mic);
    
    // データ系列作成
    /*final ObservableList<XYChart.Data<Number, Number>> data_mic =
      IntStream.range(0, (int)Math.round(10 / interval))
        .mapToObj(i -> new XYChart.Data<Number, Number>((int)Math.round(i * interval), 0.0))
        .collect(Collectors.toCollection(FXCollections::observableArrayList));*/
    
    // データ系列に名前をつける
    //final XYChart.Series<Number, Number> series_mic =
    //  new XYChart.Series<>("Fundamental Freq from mic", data_mic);
    
    // 軸を作成
    final NumberAxis xAxis_mic = new NumberAxis(
      /* axisLabel  = */ "Time (seconds)",
      /* lowerBound = */ -duration,
      /* upperBound = */ 0.0,
      /* tickUnit   = */ Le4MusicUtils.autoTickUnit(duration)
    );
    xAxis_mic.setAnimated(false);
    final NumberAxis yAxis_mic = new NumberAxis(
      /* axisLabel  = */ "Fundamental Frequency (Hz)",
      /* lowerBound = */ freqLowerBound,
      /* upperBound = */ freqUpperBound / 8,
      /* tickUnit   = */ Le4MusicUtils.autoTickUnit(freqUpperBound / 8 - freqLowerBound)
    );
    yAxis_mic.setAnimated(false);

    // チャートを作成 
    final LineChart<Number, Number> chart_mic = new LineChart<>(xAxis_mic, yAxis_mic);
    chart_mic.setTitle("Fundamental Frequency from mic");
    chart_mic.setCreateSymbols(false);
    chart_mic.setLegendVisible(false);
    chart_mic.setAnimated(false);
    chart_mic.getData().add(series_mic);
    
    // 描画ウィンドウ作成
    final Scene scene_mic = new Scene(chart_mic, 800, 600);
    scene_mic.getStylesheets().add("le4music.css");
    micInputStage.setScene(scene_mic);
    micInputStage.setTitle(getClass().getName());
    micInputStage.setOnCloseRequest(req -> executor2.shutdown());
    micInputStage.show();
    
    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////

    // オンライン処理
    player.addAudioFrameListener((frame, position) -> executor.execute(() -> {
      final double[] wframe = MathArrays.ebeMultiply(frame, window);
      final Complex[] spectrum = Le4MusicUtils.rfft(Arrays.copyOf(wframe, fftSize));
      final double posInSec = position / player.getSampleRate();

      /* スペクトログラム描画 */
      chart_sgram.addSpectrum(spectrum);

      /* 軸を更新 */
      xAxis.setUpperBound(posInSec);
      xAxis.setLowerBound(posInSec - duration);
      
      // waveform データ更新
      IntStream.range(0, player.getFrameSize()).forEach(i -> {
        data.get(i).setXValue((i + position) / player.getSampleRate());
        data.get(i).setYValue(frame[i]);
      });
      xAxis2.setLowerBound(position / player.getSampleRate());
      xAxis2.setUpperBound((position + player.getFrameSize()) / player.getSampleRate());
    }));
    
    recorder.addAudioFrameListener((frame, position) -> executor2.execute(() -> {
      final double[] wframe = MathArrays.ebeMultiply(frame, window);
      final Complex[] spectrum = Le4MusicUtils.rfft(Arrays.copyOf(wframe, fftSize));
      final double posInSec = position / recorder.getSampleRate();
      //final double nyquist = recorder.getSampleRate() / 2;
      
      double[] specAbs = new double[spectrum.length];
      for(int i = 0; i < spectrum.length; i++){
        specAbs[i] = spectrum[i].abs();
      }
      double ff = calcFF(recorder.getNyquist(), ((fftSize >> 1) + 1), specAbs);
      //series_mic.getData().add(new XYChart.Data<Number, Number>(posInSec, ff));
      
      /*IntStream.range(0, recorder.getFrameSize()).forEach(i -> {
        data_mic.get(i).setXValue(i / recorder.getSampleRate());
        data_mic.get(i).setYValue(ff);
        //series_mic.getData().add(new XYChart.Data<Number, Number>(i / recorder.getSampleRate(), ff));
      });*/
      
      if(position / 320 < 500){
        data_mic.get((int)Math.round(position / 320)).setXValue(posInSec);
        data_mic.get((int)Math.round(position / 320)).setYValue(ff);
      }else{
        for(int i = 0; i < 500 - 1; i++){
          data_mic.get(i).setXValue(data_mic.get(i+1).getXValue());
          data_mic.get(i).setYValue(data_mic.get(i+1).getYValue());
        }
        data_mic.get(499).setXValue(posInSec);
        data_mic.get(499).setYValue(ff);
      }
      
      xAxis_mic.setUpperBound(posInSec);
      xAxis_mic.setLowerBound(posInSec - duration);
    }));

    /* 録音開始 */
    Platform.runLater(player::start);
    Platform.runLater(recorder::start);
  }
  
  // 0 〜 2048 の arrnum から対応する周波数を求める
  public double freq(double nyquist, int fftSize, int arrnum){
    return nyquist / fftSize * arrnum;
  }
  
  // 基本周波数を求める
  public double calcFF(double nyquist, int fftSize, double specAbs[]){
    final double threshold = 0.005;   // 振幅が threshold 以下の音ははじく
    double fundFreq;
    
    if(specAbs[(int)argmax(specAbs)] < threshold)
      return 0;
    else    
      return freq(nyquist, fftSize, (int)argmax(specAbs));
  }
  
  public double argmax(double[] arr){
    double max = arr[0];
    double argmax = 0;
      for(int i = 0; i < arr.length-1; i++){
        if(max < arr[i+1]){
          max = arr[i+1];
          argmax = i + 1;
        }
      }
    return argmax;
  }
}
