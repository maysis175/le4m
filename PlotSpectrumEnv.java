import java.io.File;
import java.util.Arrays;
import java.util.stream.Collectors;
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

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;
import org.apache.commons.math3.complex.Complex;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;

public final class PlotSpectrumEnv extends Application {
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

	/* WAVファイル読み込み */
        final AudioInputStream stream = AudioSystem.getAudioInputStream(wavFile);
        final double[] waveform = Le4MusicUtils.readWaveformMonaural(stream);
        final AudioFormat format = stream.getFormat();
        final double sampleRate = format.getSampleRate();
        stream.close();
 
        /* fftSize = 2^p >= waveform.length を満たす fftSize を求める
         * 2^p はシフト演算で求める */
        final int fftSize = 1 << Le4MusicUtils.nextPow2(waveform.length);
        final int fftSize2 = (fftSize >> 1) + 1;
        /* 信号の長さを fftSize に伸ばし、長さが足りない部分は0で埋める。
         * 振幅を信号長で正規化する。 */
        final double[] src =
                Arrays.stream(Arrays.copyOf(waveform, fftSize))
                      .map(w -> w / waveform.length)
                      .toArray();
        /* 高速フーリエ変換を行う */
        final Complex[] spectrum = Le4MusicUtils.rfft(src);
        
        /* 対数振幅スペクトルを求める */
        final double[] specLog =
                Arrays.stream(spectrum)
                      .mapToDouble(c -> 20.0 * Math.log10(c.abs()))
                      .toArray();
        
        /* 対数振幅スペクトルをフーリエ変換 */
        final int fftSize3 = 1 << Le4MusicUtils.nextPow2(specLog.length);
        final int fftSize4 = (fftSize3 >> 1) + 1;
        final double[] src2 =
                Arrays.stream(Arrays.copyOf(specLog, fftSize3))
                      .map(w -> w / specLog.length)
                      .toArray();
        final Complex[] cepstrum = Le4MusicUtils.rfft(src2);
        final double[] cepsAbs = Arrays.stream(cepstrum)
                         .mapToDouble(c -> Math.log10(c.abs()))
                         .toArray();
        
        /* 1〜13次のケプストラム係数を抽出 */
        for(int i = 0; i < cepstrum.length; i++){
            if(i >= 13) cepstrum[i] = Complex.ZERO;
        }        
        
        /* 取り出した成分を逆フーリエ変換 */
        final int fftSize5 = 1 << Le4MusicUtils.nextPow2(cepstrum.length);
        final int fftSize6 = (fftSize5 >> 1) + 1;
        
        final Complex[] ceps_filled = new Complex[fftSize6];
        for(int i = 0; i < ceps_filled.length; i++){
            if(i < cepstrum.length) ceps_filled[i] = cepstrum[i];
            else ceps_filled[i] = Complex.ZERO;
        }
        
        final Complex[] src3 =
                Arrays.stream(ceps_filled)
                      .map(w -> w)
                      .<Complex>toArray(i -> new Complex[i]);
        final double[] specEnv = Le4MusicUtils.irfft(src3);
        
        /* データ系列を作成 */
        final ObservableList<XYChart.Data<Number, Number>> data =
                IntStream.range(0, fftSize2)
                    .mapToObj(i -> new XYChart.Data<Number, Number>(i * sampleRate / fftSize, specLog[i]))
                    .collect(Collectors.toCollection(FXCollections::observableArrayList));
        final ObservableList<XYChart.Data<Number, Number>> data2 =
                IntStream.range(0, fftSize5)
                    .mapToObj(i -> new XYChart.Data<Number, Number>(i * sampleRate / fftSize5, specEnv[i]))
                    .collect(Collectors.toCollection(FXCollections::observableArrayList));
        
        /* データ系列に名前をつける */
        //final XYChart.Series<Number, Number> series = new XYChart.Series<>("Spectrum", data);
        final XYChart.Series<Number, Number> series2 = new XYChart.Series<>("Cepstrum", data2);
        
        /* 軸を作成 */
        final NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("Frequency (Hz)");
        final NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Amplitude (dB)");
        
        /* チャートを作成 */
        final LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle("Spectrum");
        chart.setCreateSymbols(false);
        //chart.getData().add(series);
        chart.getData().add(series2);
        
        /* グラフ描画 */
        final Scene scene = new Scene(chart, 800, 600);
        
        /* ウィンドウ表示 */
        primaryStage.setScene(scene);
        primaryStage.setTitle(getClass().getName());
        primaryStage.show();
    }
}