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
                                            .mapToDouble(c -> 20.0 * Math.log10(c.abs()))
                                            .toArray())
                           .toArray(n -> new double[n][]);
        
        // クロマベクトル
        double[][] chromaVec = new double[specLog.length][17];
        for(int i = 0; i < specLog.length; i++){
            for(int j = 0; j < 17; j++){
                if(j < 12){
                    chromaVec[i][j] = 0.001 * chromaPower(specLog[i], j, sampleRate * 0.5, fftSize2);
                    if(chromaVec[i][j] == 0) chromaVec[i][j] = Integer.MIN_VALUE;
                }else{
                    chromaVec[i][j] = 0;
                }
            }
        }
        
        /*for(int i = 0; i < specLog.length; i++){
            for(int j = 0; j < 17; j++){
                if(j == 0 && freq(sampleRate * 0.5, fftSize2, i) >= 203 &&
                    freq(sampleRate * 0.5, fftSize2, i) <= 215){
                    chromaVec[i][j] = -10;
                }else{
                    chromaVec[i][j] = Integer.MIN_VALUE;
                }
            }
        }*/
        
        for(int i = 0; i < 12; i++){
            System.out.println(chromaVec[20][i]);
        }
        
        System.out.println(Arrays.toString(chromaVec[100]));
        
        System.out.println(specLog.length);
        System.out.println(specLog[100].length);
        
        /* X軸を作成 */
        final NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("Time (seconds)");
        xAxis.setLowerBound(0.0);
        xAxis.setUpperBound(specLog.length * shiftDuration);
        
        /* Y軸を作成 */
        final NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Frequency (Hz)");
        yAxis.setLowerBound(0.0);
        yAxis.setUpperBound(12);
        
        /* chチャートを作成 */
        final LineChartWithSpectrogram<Number, Number> chart =
                new LineChartWithSpectrogram<>(xAxis, yAxis);
        chart.setParameters(chromaVec.length, 17, 12);
        chart.setTitle("Spectrogram");
        Arrays.stream(chromaVec).forEach(chart::addSpecLog);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        
        /* グラフ描画 */
        final Scene scene = new Scene(chart, 800, 600);
        
        /* ウィンドウ表示 */
        primaryStage.setScene(scene);
        primaryStage.setTitle(getClass().getName());
        primaryStage.show();
    }
    
    // 0 〜 2048 の arrnum から対応する周波数を求める
    public double freq(double nyquist, int fftSize, int arrnum){
        return nyquist / fftSize * arrnum;
    }
    
    // 各音名のパワーを求める
    // toneName は 0 〜 12 で C, C#, ... B に対応
    public double chromaPower(double[] spec, int toneName, double nyquist, int fftSize){
        double halftone = Math.pow(2, 1/12);
        double powerSum = 0;
        double[] toneFreq = {261.63, 277.18, 293.66, 311.13, 329.63, 349.23,
                             369.99, 392.00, 415.30, 440.00, 466.16, 493.88};
        
        double[] baseFreq = new double[5];
        for(int i = 0; i <= 4; i++){
            baseFreq[i] = toneFreq[toneName] * Math.pow(2, i-2);
            for(int j = 0; j < spec.length; j++){
                double frameFreq = spec[j];
                if(freq(nyquist, fftSize, j) >= baseFreq[i] - baseFreq[i] * halftone / 2
                        && freq(nyquist, fftSize, j) <= baseFreq[i] + baseFreq[i] * halftone / 2){
                    powerSum += frameFreq;
                }
            }
        }
        return powerSum;
    }
}