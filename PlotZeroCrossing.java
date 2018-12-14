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

public final class PlotZeroCrossing extends Application {
    
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
                        if(sig_0 < 0.15 || sig_1 < 0.15) sig_0 = 0;
                        ac[i] += sig_0 * sig_1;
                    }
                }
                if(ac_buf < ac[i]){
                    ac_buf = ac[i]; fundFreq[i] = t;
                }
            }
        }
        
        /* ゼロ交差数計算 */
        int[] zero_crossing = new int[shiftSum];
        for(int i = 0; i < shiftSum; i++){
            zero_crossing[i] = 0;
            for(int j = 0; j < frameSize - 1; j++){
                if((waveform[i * shiftSize + j] >= 0 && waveform[i * shiftSize + j + 1] < 0)
                        || (waveform[i * shiftSize + j] <= 0 && waveform[i * shiftSize + j + 1] > 0)){
                    zero_crossing[i]++;
                }
            }
            if(zero_crossing[i] / frameDuration > 2 * fundFreq[i]){
                fundFreq[i] = 0;
            }
        }
        
        System.out.println(zero_crossing[100]);
        
        /* 基本周波数データ系列を作成 */
        final ObservableList<XYChart.Data<Number, Number>> data =
                IntStream.range(0, fundFreq.length)
                    .mapToObj(i -> new XYChart.Data<Number, Number>(i / sampleRate * shiftSize, fundFreq[i]))
                    .collect(Collectors.toCollection(FXCollections::observableArrayList));
        
        /* データ系列に名前をつける */
        final XYChart.Series<Number, Number> series = new XYChart.Series<>("Spectrum", data);
        
        /* X軸を作成 */
        final NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("Time (seconds)");
        xAxis.setLowerBound(0.0);
        xAxis.setUpperBound(specLog.length * shiftDuration);
        
        /* Y軸を作成 */
        final NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Frequency (Hz)");
        yAxis.setLowerBound(0.0);
        yAxis.setUpperBound(sampleRate * 0.5);
        
        /* 軸を作成 */
        final NumberAxis xAxis2 = new NumberAxis();
        xAxis2.setLabel("Frequency (Hz)");
        final NumberAxis yAxis2 = new NumberAxis();
        yAxis2.setLabel("Amplitude (dB)");
        
        /* chチャートを作成 */
        final LineChartWithSpectrogram<Number, Number> chart =
                new LineChartWithSpectrogram<>(xAxis, yAxis);
        chart.setParameters(specLog.length, fftSize2, sampleRate * 0.5);
        chart.setTitle("Spectrogram");
        Arrays.stream(specLog).forEach(chart::addSpecLog);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        
        /* チャートを作成 */
        final LineChart<Number, Number> chart2 = new LineChart<>(xAxis, yAxis);
        chart2.setTitle("Spectrum");
        chart2.setCreateSymbols(false);
        chart2.getData().add(series);
        
        /* グラフ描画 */
        final Scene scene = new Scene(chart, 800, 600);       
        
        /* グラフ描画 */
        final Scene scene2 = new Scene(chart2, 800, 600);
        
        /* ウィンドウ表示 */
        primaryStage.setScene(scene);
        primaryStage.setScene(scene2);
        primaryStage.setTitle(getClass().getName());
        primaryStage.show();
    }
}