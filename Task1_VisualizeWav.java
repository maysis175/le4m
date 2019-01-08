import java.lang.invoke.MethodHandles;
import java.io.File;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.IntStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.imageio.ImageIO;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.chart.XYChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.collections.ObservableList;
import javafx.collections.FXCollections;
import javafx.scene.image.WritableImage;
import javafx.embed.swing.SwingFXUtils;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.HelpFormatter;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.util.MathArrays;

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;
import jp.ac.kyoto_u.kuis.le4music.LineChartWithSpectrogram;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.apache.commons.cli.ParseException;

public final class Task1_VisualizeWav extends Application {
    
    private static final Options options = new Options();
    private static final String helpMessage = 
            MethodHandles.lookup().lookupClass().getName() + " [OPTIONS] <WAVFILE>";
    
    static{
        // コマンドラインオプション定義
        options.addOption("h", "help", false, "Display this help and exit");
        options.addOption("o", "outfile", true, "Output image file (Default: " +
                                                 MethodHandles.lookup().lookupClass().getSimpleName() +
                                                 "." + Le4MusicUtils.outputImageExt + ")");
        options.addOption("f", "frame", true,
                          "Duration of frame [seconds] (Default: " +
                          Le4MusicUtils.frameDuration + ")");
        options.addOption("s", "shift", true,
                          "Duration of shift [seconds] (Default: frame/8)");
    }
    
    @Override public final void start(final Stage primaryStage)
        throws IOException,
               UnsupportedAudioFileException,
               ParseException {
        /* コマンドライン引数処理 */
        final String[] args = getParameters().getRaw().toArray(new String[0]);
        final CommandLine cmd = new DefaultParser().parse(options, args);
        if(cmd.hasOption("help")){
            new HelpFormatter().printHelp(helpMessage, options);
            Platform.exit();
            return;
        }
        final String[] pargs = cmd.getArgs();
	if(pargs.length < 1){
	    System.out.println("WAVFILE is not given.");
            new HelpFormatter().printHelp(helpMessage, options);
	    Platform.exit();
	    return;
	}
	final File wavFile = new File(pargs[0]);
        
        /* WAVファイル読み込み */
        final AudioInputStream stream = AudioSystem.getAudioInputStream(wavFile);
        final double[] waveform = Le4MusicUtils.readWaveformMonaural(stream);
        final AudioFormat format = stream.getFormat();
        final double sampleRate = format.getSampleRate();
        final double nyquist = sampleRate * 0.5;
        stream.close();
        
        final LineChartWithSpectrogram<Number, Number> chart_sgram =
                makeSpectrogramChart(cmd, sampleRate, nyquist, waveform);
        final LineChart<Number, Number> chart_ff =
                makeFundFreqChart(cmd, sampleRate, nyquist, waveform);
        final LineChartWithSpectrogram<Number, Number> chart_chromagram =
                makeChromagramChart(cmd, sampleRate, nyquist, waveform);
        final LineChart<Number, Number> chart_chord =
                makeChordChart(cmd, sampleRate, nyquist, waveform);
        final LineChart<Number, Number> chart_melo =
                makeMelodyChart(cmd, sampleRate, nyquist, waveform);
        
        // シーングラフ作成
        HBox root = new HBox();
        VBox left = new VBox(2.0);
        VBox right = new VBox(2.0);
        root.getChildren().addAll(left, right);
        
        left.getChildren().add(chart_sgram);
        right.getChildren().add(chart_ff);
        left.getChildren().add(chart_chromagram);
        right.getChildren().add(chart_chord);
        left.getChildren().add(chart_melo);
        
        /* グラフ描画 */
        final Scene scene = new Scene(root, 1000, 1200);
        scene.getStylesheets().add("le4music.css");
        
        /* ウィンドウ表示 */
        primaryStage.setScene(scene);
        primaryStage.setTitle(getClass().getName());
        primaryStage.show();
        
        // スクショ
        Platform.runLater(() -> {
            final String[] name_ext = Le4MusicUtils.getFilenameWithImageExt(
                Optional.ofNullable(cmd.getOptionValue("outfile")),
                getClass().getSimpleName()
            );
            System.out.println(name_ext[0]);
            final WritableImage image = scene.snapshot(null);
            try {
                ImageIO.write(SwingFXUtils.fromFXImage(image, null),
                              name_ext[1], new File(name_ext[0] + "." + name_ext[1]));
            } catch(IOException e){ e.printStackTrace(); }
        });
        
    }
    
    // スペクトログラム＆チャート作成
    public LineChartWithSpectrogram<Number, Number> makeSpectrogramChart(CommandLine cmd,
                                                                     double sampleRate,
                                                                     double nyquist,
                                                                     double[] waveform){
        /* 窓関数とFFTのサンプル数 */
        final double frameDuration = 
                Optional.ofNullable(cmd.getOptionValue("frame"))
                        .map(Double::parseDouble)
                        .orElse(Le4MusicUtils.frameDuration);
        final int frameSize = (int)Math.round(frameDuration * sampleRate);
        final int fftSize = 1 << Le4MusicUtils.nextPow2(frameSize);
        final int fftSize2 = (fftSize >> 1) + 1;
        
        /* シフトのサンプル数 */
        final double shiftDuration =
                Optional.ofNullable(cmd.getOptionValue("shift"))
                        .map(Double::parseDouble)
                        .orElse(Le4MusicUtils.frameDuration / 8);
        final int shiftSize = (int)Math.round(shiftDuration * sampleRate);
        
        /* 窓関数を求め正規化する */
        final double[] window = MathArrays.normalizeArray(
            Arrays.copyOf(Le4MusicUtils.hanning(frameSize), fftSize), 1.0
        );
        
        /* 短時間フーリエ変換本体 */
        final Stream<Complex[]> spectrogram =
                Le4MusicUtils.sliding(waveform, window, shiftSize)
                             .map(frame -> Le4MusicUtils.rfft(frame));
        
        /* 複素スペクトログラムを対数振幅スペクトログラムに */
        final double[][] specLog =
                spectrogram.map(sp -> Arrays.stream(sp)
                                            .mapToDouble(c -> 20.0 * Math.log10(c.abs()))
                                            .toArray())
                           .toArray(n -> new double[n][]);
        
        /* X軸を作成 */
        final double duration = (specLog.length - 1) * shiftDuration;
        final NumberAxis xAxis = new NumberAxis("Time (seconds)", 0.0, duration,
                                                Le4MusicUtils.autoTickUnit(duration)
        );
        xAxis.setAnimated(false);
        
        /* Y軸を作成 */
        final NumberAxis yAxis = new NumberAxis("Frequence (Hz)", 0.0, nyquist,
                                                Le4MusicUtils.autoTickUnit(nyquist)
        );
        yAxis.setAnimated(false);
        
        /* chチャートを作成 */
        final LineChartWithSpectrogram<Number, Number> chart =
                new LineChartWithSpectrogram<>(xAxis, yAxis);
        chart.setParameters(specLog.length, fftSize2, nyquist);
        chart.setTitle("Spectrogram");
        Arrays.stream(specLog).forEach(chart::addSpecLog);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        
        return chart;
    }
    
    // 基本周波数＆チャート作成
    public LineChart<Number, Number> makeFundFreqChart(CommandLine cmd,
                                                                     double sampleRate,
                                                                     double nyquist,
                                                                     double[] waveform){
        // 1フレームの長さ(sec)とフレームシフト幅(sec)
        final double frameDuration = Le4MusicUtils.frameDuration;   // 0.2
        final double shiftDuration = frameDuration / 8.0;           // 0.025
        
        /* 1フレームとシフトのサンプル数 */
        final int frameSize = (int)Math.round(frameDuration * sampleRate);  // 3200 per sec
        final int shiftSize = (int)Math.round(shiftDuration * sampleRate);  // 400 per sec
        
        /* 基本周波数計算 */
        int shiftSum = (int)Math.round((waveform.length - frameSize) / shiftSize);
        double[] fundFreq = new double[shiftSum];
        double[] ac = new double[shiftSum];
        double ac_buf = 0;
        for(int i = 0; i < shiftSum; i++){
            ac_buf = 0;
            for(int t = 10; t < (int)Math.round(frameSize-1); t++){
                ac[i]=0;
                for(int j = 0; j < frameSize - 1; j++){
                    if(i * shiftSize + j + t >= waveform.length){
                        ac[i] += 0;
                    }else{
                        double sig_0 = waveform[i * shiftSize + j];
                        double sig_1 = waveform[i * shiftSize + j + t];
                        
                        // 小さい音ははじく
                        if(sig_0 < 0.1 || sig_1 < 0.1) sig_0 = 0;
                        ac[i] += sig_0 * sig_1;
                    }
                }
                if(ac_buf < ac[i]){
                    ac_buf = ac[i]; fundFreq[i] = sampleRate / t;
                }
            }
        }
        
        /* データ系列を作成 */
        final ObservableList<XYChart.Data<Number, Number>> data =
                IntStream.range(0, fundFreq.length)
                    .mapToObj(i -> new XYChart.Data<Number, Number>(i / sampleRate * shiftSize, fundFreq[i]))
                    .collect(Collectors.toCollection(FXCollections::observableArrayList));
        
        /* データ系列に名前をつける */
        final XYChart.Series<Number, Number> series = new XYChart.Series<>("Fundamental Frequency", data);
        
        /* x軸を作成 */
        final double duration = (waveform.length - 1) / sampleRate;
        final NumberAxis xAxis = new NumberAxis("Time (seconds)", 0.0, duration,
                                                Le4MusicUtils.autoTickUnit(duration));
        xAxis.setAnimated(false);
        
        /* y軸を作成 */
        final double freqLowerBound =
                Optional.ofNullable(cmd.getOptionValue("freq-lo"))
                        .map(Double::parseDouble)
                        .orElse(0.0);
        if(freqLowerBound < 0.0)
            throw new IllegalArgumentException(
                "freq-lo must be non-negatice: " + freqLowerBound
            );
        final double freqUpperBound =
                Optional.ofNullable(cmd.getOptionValue("freq-up"))
                        .map(Double::parseDouble)
                        .orElse(nyquist);
        if(freqUpperBound <= freqLowerBound)
            throw new IllegalArgumentException(
                "freq-up must be larger than freq-lo: " +
                "freq-lo = " + freqLowerBound + ", freq-up = " + freqUpperBound
            );
        final NumberAxis yAxis = new NumberAxis("Frequency (Hz)", freqLowerBound, 500,
                                                Le4MusicUtils.autoTickUnit(500 - freqLowerBound));
        yAxis.setAnimated(false);
        
        /* チャートを作成 */
        final LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle("Fundamental Frequency");
        chart.setCreateSymbols(false);
        chart.getData().add(series);
        
        return chart;
    }
    
    // クロマグラム＆チャート作成
    public LineChartWithSpectrogram<Number, Number> makeChromagramChart(CommandLine cmd,
                                                                           double sampleRate,
                                                                           double nyquist,
                                                                           double[] waveform){
        // 1フレームの長さ(sec)とフレームシフト幅(sec)
        final double frameDuration = Le4MusicUtils.frameDuration;   // 0.2
        final double shiftDuration = frameDuration / 8.0;           // 0.025
        
        // クロマベクトル作成
        double[][] chromaVec = makeChromaVector(sampleRate, waveform);
        
        // X軸を作成 
        final double duration = (chromaVec.length - 1) * shiftDuration;
        final NumberAxis xAxis = new NumberAxis("Time (seconds)", 0.0, duration,
                                                Le4MusicUtils.autoTickUnit(duration));
        xAxis.setAnimated(false);
        
        // Y軸を作成 
        final NumberAxis yAxis = new NumberAxis("Chromagram (0-12)", 0.0, 12,
                                                Le4MusicUtils.autoTickUnit(12));
        yAxis.setAnimated(false);
        
        // chチャートを作成 
        final LineChartWithSpectrogram<Number, Number> chart =
                new LineChartWithSpectrogram<>(xAxis, yAxis);
        chart.setParameters(chromaVec.length, 12, 12);
        chart.setTitle("Chromagram");
        Arrays.stream(chromaVec).forEach(chart::addSpecLog);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        
        return chart;
    }
    
    // コード進行＆チャート作成
    public LineChart<Number, Number> makeChordChart(CommandLine cmd,
                                                      double sampleRate,
                                                      double nyquist,
                                                      double[] waveform){
        // クロマベクトル作成
        double[][] chromaVec = makeChromaVector(sampleRate, waveform);
        
        // 和音らしさ
        // like_chord[i][j] : フレーム i の和音 j らしさ
        // 0 <= j <= 11 : C, C#, ... , B Maj
        // 12 <= j <= 23 : C, C#, ... , B Min
        double[][] like_chord = new double[chromaVec.length][(12 * 2)];
        double a_root = 1.0, a_3rd = 0.5, a_5th = 0.8;
        for(int i = 0; i < chromaVec.length; i++){
            for(int j = 0; j < 12 * 2; j++){
                if(j < 12){
                    like_chord[i][j] = a_root * chromaVec[i][j]
                                     + a_3rd  * chromaVec[i][(j + 4) % 12]
                                     + a_5th  * chromaVec[i][(j + 7) % 12];
                }else if(j < 24){
                    like_chord[i][j] = a_root * chromaVec[i][j % 12]
                                     + a_3rd  * chromaVec[i][(j + 3) % 12]
                                     + a_5th  * chromaVec[i][(j + 7) % 12];
                }
            }
        }
        
        // 和音らしさをグラフ化
        double[] like_graph = new double[like_chord.length];
        for(int i = 0; i < like_graph.length; i++){
            like_graph[i] = argmax(like_chord[i]);
        }
        
        // データ系列を作成 
        final ObservableList<XYChart.Data<Number, Number>> data =
                IntStream.range(0, like_graph.length)
                    .mapToObj(i -> new XYChart.Data<Number, Number>(i * sampleRate / waveform.length / 5, like_graph[i]))
                    .collect(Collectors.toCollection(FXCollections::observableArrayList));
        
        // データ系列に名前をつける 
        final XYChart.Series<Number, Number> series = new XYChart.Series<>("Chord Progression", data);
        
        /* x軸を作成 */
        final double duration = (waveform.length - 1) / sampleRate;
        final NumberAxis xAxis = new NumberAxis("Time (seconds)", 0.0, duration,
                                                Le4MusicUtils.autoTickUnit(duration));
        xAxis.setAnimated(false);
        
        /* y軸を作成 */
        final NumberAxis yAxis = new NumberAxis("Chord (0-24)", 0, 24,
                                                Le4MusicUtils.autoTickUnit(24));
        yAxis.setAnimated(false);
        
        // チャートを作成 
        final LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle("Chord Progression");
        chart.setCreateSymbols(false);
        chart.getData().add(series);
        
        return chart;
    }
    
    
    // クロマベクトル作成
    public double[][] makeChromaVector(double sampleRate,
                                         double[] waveform){
        // 1フレームの長さ(sec)とフレームシフト幅(sec)
        final double frameDuration = Le4MusicUtils.frameDuration;   // 0.2
        final double shiftDuration = frameDuration / 8.0;           // 0.025
        
        /* 窓関数とFFTのサンプル数 */
        final int frameSize = (int)Math.round(frameDuration * sampleRate);
        final int fftSize = 1 << Le4MusicUtils.nextPow2(frameSize);
        final int fftSize2 = (fftSize >> 1) + 1;
        
        /* シフトのサンプル数 */
        final int shiftSize = (int)Math.round(shiftDuration * sampleRate);
        
        /* 窓関数を求め正規化する */
        final double[] window = MathArrays.normalizeArray(
            Arrays.copyOf(Le4MusicUtils.hanning(frameSize), fftSize), 1.0
        );
        
        /* 短時間フーリエ変換本体 */
        final Stream<Complex[]> spectrogram =
                Le4MusicUtils.sliding(waveform, window, shiftSize)
                             .map(frame -> Le4MusicUtils.rfft(frame));
        
        /* 複素スペクトログラムを対数振幅スペクトログラムに */
        final double[][] specLog =
                spectrogram.map(sp -> Arrays.stream(sp)
                                            .mapToDouble(c -> c.abs())
                                            .toArray())
                           .toArray(n -> new double[n][]);
        
        // クロマベクトル
        double[][] chromaVec = new double[specLog.length][17];
        for(int i = 0; i < specLog.length; i++){
            for(int j = 0; j < 17; j++){
                if(j < 12){
                    chromaVec[i][j] = -0.15 / chromaPower(specLog[i], j, sampleRate * 0.5, fftSize2);
                    if(chromaVec[i][j] == 0) chromaVec[i][j] = Integer.MIN_VALUE;
                }else{
                    chromaVec[i][j] = 0;
                }
            }
        }
        
        return chromaVec;
    }

    // 認識したメロディ＆チャートの表示
    public LineChart<Number, Number> makeMelodyChart(CommandLine cmd,
                                                      double sampleRate,
                                                      double nyquist,
                                                      double[] waveform){
        // 1フレームの長さ(sec)とフレームシフト幅(sec)
        final double frameDuration = Le4MusicUtils.frameDuration;   // 0.2
        final double shiftDuration = frameDuration / 8.0;           // 0.025
        
        // 窓関数とFFTのサンプル数 
        final int frameSize = (int)Math.round(frameDuration * sampleRate);
        final int fftSize = 1 << Le4MusicUtils.nextPow2(frameSize);
        final int fftSize2 = (fftSize >> 1) + 1;
        
        // シフトのサンプル数 
        final int shiftSize = (int)Math.round(shiftDuration * sampleRate);
        
        // 窓関数を求め正規化する 
        final double[] window = MathArrays.normalizeArray(
            Arrays.copyOf(Le4MusicUtils.hanning(frameSize), fftSize), 1.0
        );
        
        // 短時間フーリエ変換本体 
        final Stream<Complex[]> spectrogram =
                Le4MusicUtils.sliding(waveform, window, shiftSize)
                             .map(frame -> Le4MusicUtils.rfft(frame));
        
        // 複素スペクトログラムを対数振幅スペクトログラムに 
        final double[][] specLog =
                spectrogram.map(sp -> Arrays.stream(sp)
                                            .mapToDouble(c -> c.abs())
                                            .toArray())
                           .toArray(n -> new double[n][]);
        
        // 基本周波数の候補集合
        // 候補集合は3オクターブの範囲で設定
        double[] ffCandSet = new double[360];
        for(int i = 0; i < ffCandSet.length; i++){
            ffCandSet[i] = 60 + i * 0.1;
        }
        
        // fundFreq[i][j] : フレーム i の音階 j らしさ
        // ノートナンバー : N ? N + 32 とし、 0.1 刻みで候補とする
        // ノートナンバー = N + j * 0.1
        // j = 10 * (ノートナンバー - N)
        int N = 60;     // 候補集合の最低音のノートナンバー
        double[][] fundFreq = new double[specLog.length][ffCandSet.length];
        for(int i = 0; i < fundFreq.length; i++){
            for(int j = 0; j < ffCandSet.length; j++){
                fundFreq[i][j] = melo_SHS(specLog[i], N + j * 0.1, nyquist, fftSize2);
            }
        }
        
        // 音高推定
        double[] meloLike = new double[specLog.length];
        for(int i = 0; i < specLog.length; i++){
            meloLike[i] = N + argmax(fundFreq[i]) / 10;
        }
        
        // データ系列を作成 
        final ObservableList<XYChart.Data<Number, Number>> data =
                IntStream.range(0, meloLike.length)
                    .mapToObj(i -> new XYChart.Data<Number, Number>(i * sampleRate / waveform.length / 5, meloLike[i]))
                    .collect(Collectors.toCollection(FXCollections::observableArrayList));
        
        // データ系列に名前をつける 
        final XYChart.Series<Number, Number> series = new XYChart.Series<>("Melody", data);
        
        /* x軸を作成 */
        final double duration = (waveform.length - 1) / sampleRate;
        final NumberAxis xAxis = new NumberAxis("Time (seconds)", 0.0, duration,
                                                Le4MusicUtils.autoTickUnit(duration));
        xAxis.setAnimated(false);
        
        /* y軸を作成 */
        final NumberAxis yAxis = new NumberAxis("Mote Number", N, N + 36,
                                                Le4MusicUtils.autoTickUnit(36));
        yAxis.setAnimated(false);
        
        // チャートを作成 
        final LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle("Melody");
        chart.setCreateSymbols(false);
        chart.getData().add(series);
        
        return chart;
    }
    
    // ノートナンバーから周波数への変換
    public double n_to_f(double noteNum){
        return (440 * Math.pow(2, ((noteNum-69)/12)));
    }
    
    // 周波数からノートナンバーへの変換
    public int f_to_n(double freq){
        return (int)Math.round(12 * Math.log(freq / 440) / Math.log(2) + 69);
    }
    
    // 0 ? 2048 の arrnum から対応する周波数を求める
    public double freq(double nyquist, int fftSize, int arrnum){
        return nyquist / fftSize * arrnum;
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
    
    // 各音名のパワーを求める
    // toneName は 0 ? 12 で C, C#, ... B に対応
    public double chromaPower(double[] spec, int toneName, double nyquist, int fftSize){
        double halfhalftone = Math.pow(2.0, 1.0/30.0);
        double powerSum = 0;
        double[] toneFreq = new double[12];
        for(int i = 0; i < 12 ; i++){
            toneFreq[i] = n_to_f(60+i);
        }
        
        double[] baseFreq = new double[5];
        int div = 1;
        for(int i = 0; i <= 4; i++){
            baseFreq[i] = toneFreq[toneName] * Math.pow(2.0, (double)(i-2));
            for(int j = 0; j < spec.length; j++){
                double frameFreq = spec[j];
                if(freq(nyquist, fftSize, j) >= baseFreq[i] / halfhalftone
                        && freq(nyquist, fftSize, j) <= baseFreq[i] * halfhalftone){
                    powerSum += frameFreq;
                    div++;
                }
            }
        }
        return powerSum / div;
    }
    
    // SHSによりメロディの温厚を推定
    public double melo_SHS(double[] spec, double toneNum, double nyquist, int fftSize){
        int N = 5;  // 第 N 倍音までとる
        double candFreq = n_to_f(toneNum);
        double powerSum = 0;
        
        for(int i = 0; i < spec.length; i++){
            for(int j = 1; j <= N; j++){
                if(Math.abs(candFreq * j - freq(nyquist, fftSize, i)) < 10){
                    powerSum += spec[i];
                }
            }
        }
        
        return Math.abs(powerSum);
    }
}
