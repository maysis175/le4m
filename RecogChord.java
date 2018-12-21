import java.io.File;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.IntStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.chart.XYChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.collections.ObservableList;
import javafx.collections.FXCollections;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.util.MathArrays;

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;
import jp.ac.kyoto_u.kuis.le4music.LineChartWithSpectrogram;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;

public final class RecogChord extends Application {
    
    @Override public final void start(final Stage primaryStage)
        throws IOException,
               UnsupportedAudioFileException {
        /* コマンドライン引数処理 */
        final String[] args = getParameters().getRaw().toArray(new String[0]);
        if(args.length < 1){
            System.out.println("WAVFILE is not given.");
            Platform.exit();
            return;
        }
        final File wavFile = new File(args[0]);
        
        final double frameDuration = Le4MusicUtils.frameDuration;
        final double shiftDuration = frameDuration / 8.0;
        
        /* WAVファイル読み込み */
        final AudioInputStream stream = AudioSystem.getAudioInputStream(wavFile);
        final double[] waveform = Le4MusicUtils.readWaveformMonaural(stream);
        final AudioFormat format = stream.getFormat();
        final double sampleRate = format.getSampleRate();
        stream.close();
        
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
        
        for(int i = 240; i <= 245; i++){
            System.out.println(Arrays.toString(chromaVec[i]));
            System.out.println(Arrays.toString(like_chord[i]));
        }
        System.out.println(like_graph[242]);
        
        
        //////////////////////////////////////////////////
        // クロマグラム出すときはこっち
        //////////////////////////////////////////////////
        
        // X軸を作成 
        /*final NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("Time (seconds)");
        xAxis.setLowerBound(0.0);
        xAxis.setUpperBound(specLog.length * shiftDuration);
        
        // Y軸を作成 
        final NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Chroma Vector");
        yAxis.setLowerBound(0.0);
        yAxis.setUpperBound(16);
        
        // chチャートを作成 
        final LineChartWithSpectrogram<Number, Number> chart =
                new LineChartWithSpectrogram<>(xAxis, yAxis);
        chart.setParameters(chromaVec.length, 17, 12);
        chart.setTitle("Spectrogram");
        Arrays.stream(chromaVec).forEach(chart::addSpecLog);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        
        // グラフ描画 
        final Scene scene = new Scene(chart, 800, 600);
        
        // ウィンドウ表示 
        primaryStage.setScene(scene);
        primaryStage.setTitle(getClass().getName());
        primaryStage.show();*/
        
        
        //////////////////////////////////////////////////
        // コード進行出すときはこっち
        //////////////////////////////////////////////////
        
        // データ系列を作成 
        final ObservableList<XYChart.Data<Number, Number>> data =
                IntStream.range(0, like_graph.length)
                    .mapToObj(i -> new XYChart.Data<Number, Number>(i * sampleRate / waveform.length / 5, like_graph[i]))
                    .collect(Collectors.toCollection(FXCollections::observableArrayList));
        
        // データ系列に名前をつける 
        final XYChart.Series<Number, Number> series = new XYChart.Series<>("Spectrum", data);
        
        // 軸を作成 
        final NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("Time (sec)");
        final NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Chord");
        
        // チャートを作成 
        final LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle("Spectrum");
        chart.setCreateSymbols(false);
        chart.getData().add(series);
        
        // グラフ描画 
        final Scene scene = new Scene(chart, 800, 600);
        
        // ウィンドウ表示 
        primaryStage.setScene(scene);
        primaryStage.setTitle(getClass().getName());
        primaryStage.show();
    }
    
    // ノートナンバーから周波数への変換
    public double n_to_f(double noteNum){
        return (440 * Math.pow(2, ((noteNum-69)/12)));
    }
    
    // 周波数からノートナンバーへの変換
    public int f_to_n(double freq){
        return (int)Math.round(12 * Math.log(freq / 440) / Math.log(2) + 69);
    }
    
    // 0 〜 2048 の arrnum から対応する周波数を求める
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
    // toneName は 0 〜 12 で C, C#, ... B に対応
    public double chromaPower(double[] spec, int toneName, double nyquist, int fftSize){
        double halfhalftone = Math.pow(2.0, 1.0/30.0);
        double powerSum = 0;
        /*double[] toneFreq = {261.63, 277.18, 293.66, 311.13, 329.63, 349.23,
                             369.99, 392.00, 415.30, 440.00, 466.16, 493.88};*/
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
}