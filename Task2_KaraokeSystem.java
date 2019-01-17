import java.lang.invoke.MethodHandles;
import java.io.File;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sound.sampled.AudioSystem;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.text.Text;
import javafx.scene.text.Font;
import javafx.scene.chart.XYChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.layout.VBox;
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
import jp.ac.kyoto_u.kuis.le4music.LineChartWithMarkers;
import static jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils.verbose;

import java.io.IOException;
import java.util.stream.Collectors;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.LineUnavailableException;
import org.apache.commons.cli.ParseException;

public final class Task2_KaraokeSystem extends Application {

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
    Stage micSgramStage = new Stage();
    
    // ウィンドウ位置調整
    primaryStage.setX(100);
    primaryStage.setY(100);
    secondaryStage.setX(1000);
    secondaryStage.setY(100);
    micInputStage.setX(100);
    micInputStage.setY(1000);
    micSgramStage.setX(1000);
    micSgramStage.setY(1000);

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
    final ExecutorService executor2 = Executors.newSingleThreadExecutor();

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
    // マイク入力から Waveform 作成
    ////////////////////////////////////////////////////////////////////////////

    /* データ系列を作成 */
    final ObservableList<XYChart.Data<Number, Number>> data =
      IntStream.range(0, recorder.getFrameSize())
        .mapToObj(i -> new XYChart.Data<Number, Number>(i / recorder.getSampleRate(), 0.0))
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
    chart.setTitle("Waveform from mic");
    chart.setCreateSymbols(false);
    chart.setLegendVisible(false);
    chart.setAnimated(false);
    chart.getData().add(series);

    /* 描画ウインドウ作成 */
    final Scene scene2  = new Scene(chart, 800, 600);
    scene2.getStylesheets().add("le4music.css");
    secondaryStage.setScene(scene2);
    secondaryStage.setTitle(getClass().getName());
    secondaryStage.setOnCloseRequest(req -> executor2.shutdown());
    secondaryStage.show();
    
    ////////////////////////////////////////////////////////////////////////////
    // マイク入力から 基本周波数を表示するLineChart 作成
    ////////////////////////////////////////////////////////////////////////////
    
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
    // マイク入力から 入力された音階を出力するスペクトログラム(?) 作成
    ////////////////////////////////////////////////////////////////////////////
    
    // ノートナンバーの下限、上限
    final double nnLowerBound = 36.0;
    final double nnUpperBound = nnLowerBound + 36.0;
    
    // 軸を作成
    final NumberAxis xAxis_micS = new NumberAxis(
      /* axisLabel  = */ "Time (seconds)",
      /* lowerBound = */ -duration,
      /* upperBound = */ 0.0,
      /* tickUnit   = */ Le4MusicUtils.autoTickUnit(duration)
    );
    xAxis_micS.setAnimated(false);
    final NumberAxis yAxis_micS = new NumberAxis(
      /* axisLabel  = */ "Pitch (0-120)",
      /* lowerBound = */ 0,
      /* upperBound = */ 360,
      /* tickUnit   = */ 10
    );
    yAxis_micS.setAnimated(false);
    
    // スペクトログラム表示チャート
    final LineChartWithSpectrogram<Number, Number> chart_micSgram =
      new LineChartWithSpectrogram<>(xAxis_micS, yAxis_micS);
    chart_micSgram.setParameters(frames, 360, 360);
    chart_micSgram.setMaxHeight(600);
    chart_micSgram.setTitle("Pitch from mic");
    chart_micSgram.setLegendVisible(false);
    
    // 音高表示部
    VBox root = new VBox(15);

    Text notes = new Text("   Note Name: ");
    notes.setFont(Font.font(null, 40));
    root.getChildren().addAll(notes, chart_micSgram);
    
    // グラフ描画
    final Scene scene_micS = new Scene(root, 800, 500);    
    scene_micS.getStylesheets().add("le4music.css");
    micSgramStage.setScene(scene_micS);
    micSgramStage.setTitle(getClass().getName());
    // ウィンドウを閉じた時に他スレッドも停止させる
    micSgramStage.setOnCloseRequest(req -> executor2.shutdown());
    micSgramStage.show();
    
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
    }));
    
    recorder.addAudioFrameListener((frame, position) -> executor2.execute(() -> {
      final double[] wframe = MathArrays.ebeMultiply(frame, window);
      final Complex[] spectrum = Le4MusicUtils.rfft(Arrays.copyOf(wframe, fftSize));
      final double posInSec = position / recorder.getSampleRate();
      
      // 複素スペクトログラムを振幅スペクトログラムに変換
      double[] specAbs = new double[spectrum.length];
      for(int i = 0; i < spectrum.length; i++){
        specAbs[i] = spectrum[i].abs();
      }
      // 基本周波数
      double ff = calcFF(frame, recorder.getSampleRate());
      if(Math.abs(ff - 1600) < 0.01) ff = 0;
      
      // ノートナンバー
      double[] nn_mic = calcNoteNumberFromMic(recorder.getNyquist(), fftSize_mic2,
                                                specAbs);
      
      // 基本周波数 データ更新
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
      
      // チャート & 音階表示部更新
      chart_micSgram.addSpecAbs(nn_mic);
      notes.setText("   Note Name: " + setNoteName(nn_mic));
      
      // waveform データ更新
      IntStream.range(0, recorder.getFrameSize()).forEach(i -> {
        data.get(i).setXValue((i + position) / recorder.getSampleRate());
        data.get(i).setYValue(frame[i]);
      });
      xAxis2.setLowerBound(position / recorder.getSampleRate());
      xAxis2.setUpperBound((position + recorder.getFrameSize()) / recorder.getSampleRate());
      
      // 軸を更新
      xAxis_mic.setUpperBound(posInSec);
      xAxis_mic.setLowerBound(posInSec - duration);
      xAxis_micS.setUpperBound(posInSec);
      xAxis_micS.setLowerBound(posInSec - duration);
    }));

    /* 録音開始 */
    Platform.runLater(player::start);
    Platform.runLater(recorder::start);
  }
  
  // 基本周波数を求める
  public double calcFF(double[] frame, double sampleRate){
    double fundFreq;
    double[] autocor = new double[frame.length-11];
    Arrays.fill(autocor, 0);
    
    for(int tau = 10; tau < frame.length-1; tau++){
      for(int t = 0; t < frame.length - tau - 1; t++){
        double wf_0 = frame[t], wf_1 = frame[t + tau];
        if(frame[t] < 0.04 || frame[t + tau] < 0.04)
          wf_0 = 0;
        autocor[tau-10] += wf_0 * wf_1;
      }
    }
    
    return sampleRate / (argmax(autocor) + 10.0);
  }
  
  // マイク入力の音声のノートナンバーを求める
  // Note Number = 36 + 0.1 * i
  // i = 10 * (Note Number - 36);  
  public double[] calcNoteNumberFromMic(double nyquist,
                                             int fftSize,
                                             double[] specAbs){
    double[] nn_mic = new double[360];
    Arrays.fill(nn_mic, 0);
    
    final int N = 36;
    double[] fundFreq = new double[360];
    for(int i = 0; i < 360; i++){
      fundFreq[i] = melo_SHS(specAbs, N + i * 0.1, nyquist, fftSize);
    }
    
    if(Math.abs(fundFreq[(int)argmax(fundFreq)]) >= 0.005)
     nn_mic[(int)argmax(fundFreq)] = 0.02;
    return nn_mic;
  }
  
  // SHSによりメロディの音高を推定
  public double melo_SHS(double[] spec, double toneNum,
                          double nyquist, int fftSize){
    int N = 5;  // 第 (N-1) 倍音までとる
    double candFreq = n_to_f(toneNum);
    double powerSum = 0;
    
    for(int i = 0; i < spec.length; i++){
      for(int j = 1; j <= N; j++){
        if(Math.abs(candFreq * j - freq(nyquist, fftSize, i)) < 10){
          powerSum += spec[i];
        }
      };
    }
    
    if(Math.abs(powerSum) < 0.005){
      powerSum = 0;
    }
        
    return Math.abs(powerSum);
  }
  
  // 音高の入った配列からノートネームを出力
  public String setNoteName(double[] nn_mic){
    final String[] noteNameArr = {"C", "C#", "D", "D#", "E", "F",
                                  "F#", "G", "G#", "A", "A#", "B"};
    
    int argmax = (int)argmax(nn_mic);
    if(Math.abs(nn_mic[argmax]) < 0.02)
      return "  ";
    else{
      String pitchClass = "";
      int octave = 0;
      for(int i = 0; i <= 36; i++){
        if(i * 10 - 5 <= argmax && argmax < i * 10 + 5){
          pitchClass = noteNameArr[i % 12];
          octave = (int)Math.round(i / 12) + 2;
        }
      }
      
      return (pitchClass + String.valueOf(octave));
    }
  }
  
  // 0 〜 2048 の arrnum から対応する周波数を求める
  public double freq(double nyquist, int fftSize, int arrnum){
    return nyquist / (double)fftSize * (double)arrnum;
  }
  
  // ノートナンバーから周波数への変換
  public double n_to_f(double noteNum){
    return (440 * Math.pow(2, ((noteNum-69)/12)));
  }
  
  // 周波数からノートナンバーへの変換
  public double f_to_n(double freq){
    return (12.0 * Math.log(freq / 440.0) / Math.log(2) + 69.0);
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
