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

public final class LearnSpeechRecog_R extends Application {
    
    @Override public final void start(final Stage primaryStage)
        throws IOException,
               UnsupportedAudioFileException {
        /* コマンドライン引数処理 */
        final String[] args = getParameters().getRaw().toArray(new String[0]);
        if(args.length < 1){
            System.out.println("WAVFILE is not given.");
            Platform.exit();
            return;
        }else if(args.length < 2){
            System.out.println("Two WAVFILEs need given.");
            Platform.exit();
            return;
        }
        final File wavFile = new File(args[0]);
        final File wavFile2 = new File(args[1]);
        
        final double frameDuration = Le4MusicUtils.frameDuration;
        final double shiftDuration = frameDuration / 8.0;
        
        /* WAVファイル読み込み */
        final AudioInputStream stream = AudioSystem.getAudioInputStream(wavFile);
        final double[] waveform = Le4MusicUtils.readWaveformMonaural(stream);
        final AudioFormat format = stream.getFormat();
        final double sampleRate = format.getSampleRate();
        stream.close();
        
        /* 1フレームのサンプル数 */
        final int frameSize = (int)Math.round(frameDuration * sampleRate);
        
        /* シフトのサンプル数 */
        final int shiftSize = (int)Math.round(shiftDuration * sampleRate);
        
        // aiueo_continuous.wav のあいうえおサンプル
        // 分けたやつ
        final double[] aiueo_sep = {1.1, 2.1, 2.8, 3.66, 4.59, 5.9};
        
        // wave_vowel[i][j] : 各母音 i のフレーム j における音声データ (length = frameSize)
        double[][][] wave_vowel = new double[5][(int)Math.round(sampleRate * 2 / shiftSize)][frameSize];
        for(int i = 0; i < 5; i++){
            for(int j = 0; j < (int)Math.round(sampleRate * (aiueo_sep[i+1] - aiueo_sep[i]) / shiftSize);
                    j++){
                for(int k = 0; k < frameSize; k++){
                    wave_vowel[i][j][k] =
                            waveform[(int)Math.round(sampleRate * aiueo_sep[i]) + j * shiftSize + k];
                }
            }
        }
        
        // 各母音の各フレームごとにケプストラムを求める
        // fftSize[i][j] : 母音 i におけるフレーム j の音声信号 (length = frameSize)
        int shift2sec = (int)Math.round(sampleRate * 2 / shiftSize);
        double[][][] cepstrum_re = new double[5][shift2sec][13];
        
        double[] zero = new double[13];
        for(int i = 0; i < 5; i++){
            for(int j = 0; j < shift2sec; j++){
                Arrays.fill(cepstrum_re[i][j], 0);
            }
        }
        
        for(int i = 0; i < 5; i++){
            for(int j = 0; j < wave_vowel[i].length; j++){
                int wv_length = wave_vowel[i][j].length;
                
                int[][] fftSize = new int[5][shift2sec];
                int[][] fftSize2 = new int[5][shift2sec];
                fftSize[i][j] = 1 << Le4MusicUtils.nextPow2(wave_vowel[i][j].length);
                fftSize2[i][j] = (fftSize[i][j] >> 1) + 1;
                
                double[][][] src = new double[5][shift2sec][];
                src[i][j] = Arrays.stream(Arrays.copyOf(wave_vowel[i][j], fftSize[i][j]))
                                  .map(w -> w / wv_length)
                                  .toArray();
                
                Complex[][][] spectrum = new Complex[5][shift2sec][];
                spectrum[i][j] = Le4MusicUtils.rfft(src[i][j]);
                
                double[][][] specLog = new double[5][shift2sec][];
                specLog[i][j] = Arrays.stream(spectrum[i][j])
                                      .mapToDouble(c -> 20.0 * Math.log10(c.abs()))
                                      .toArray();
                
                int[][] fftSize3 = new int[5][shift2sec];
                int[][] fftSize4 = new int[5][shift2sec];
                fftSize3[i][j] = 1 << Le4MusicUtils.nextPow2(specLog[i][j].length);
                fftSize4[i][j] = (fftSize[i][j] >> 1) + 1;
                
                double[][][] src2 = new double[5][shift2sec][];
                src2[i][j] = Arrays.stream(Arrays.copyOf(specLog[i][j], fftSize3[i][j]))
                             .map(w -> w)
                             .toArray();
                Complex cepstrum[][][] = new Complex[5][shift2sec][];
                cepstrum[i][j] = Le4MusicUtils.rfft(src2[i][j]);
                if(j >= (int)Math.round(sampleRate * (aiueo_sep[i+1] - aiueo_sep[i]) / shiftSize))
                    Arrays.fill(cepstrum[i][j], Complex.ZERO);
                
                for(int k = 0; k < cepstrum[i][j].length; k++){
                    if(j < cepstrum[i].length){
                        if(k >= 13){
                            cepstrum[i][j][k] = Complex.ZERO;
                        }else{
                            cepstrum_re[i][j][k] = cepstrum[i][j][k].getReal();
                        }
                    }
                }
            }
        }
        
        /* パラメータの学習 */
        // N = shift2sec
        // D = 13
        double[][] mu = new double[5][13];
        double[][] sigma2 = new double[5][13];
        for(int i = 0; i < 5; i++){
            Arrays.fill(mu[i], 0);
            Arrays.fill(sigma2[i], 0);
            for(int d = 0; d < 13; d++){
                for(int n = 0; n < shift2sec; n++){
                    mu[i][d] = mu[i][d] + cepstrum_re[i][n][d] / shift2sec;
                }
                for(int n = 0; n < shift2sec; n++){
                    sigma2[i][d] = sigma2[i][d] + Math.pow(cepstrum_re[i][n][d] - mu[i][d], 2) / shift2sec;
                }
                //sigma2[i][d] = sigma2[i][d] / shift2sec;
            }
        }
        
        ///////////////////////////////////////////////////////////////////////////////////////
        ///////////////////////////////////////////////////////////////////////////////////////
        
        /* テスト用WAVファイル読み込み */
        final AudioInputStream stream2 = AudioSystem.getAudioInputStream(wavFile2);
        final double[] waveform2 = Le4MusicUtils.readWaveformMonaural(stream2);
        final AudioFormat format2 = stream2.getFormat();
        final double sampleRate2 = format2.getSampleRate();
        stream2.close();

        /* テスト音声のフレームへの切り分け */
        int shiftSum = (int)Math.round((waveform.length - frameSize) / shiftSize);
        double[][] wave2_frame = new double[shiftSum][frameSize];
        for(int i = 0; i < shiftSum; i++){
            for(int j = 0; j < frameSize; j++){
                wave2_frame[i][j] = waveform2[i * shiftSum + j];
            }
        }
        
        /* 各フレームごとにフーリエ変換 */
        Complex[][] cepstrum = new Complex[shiftSum][];
        for(int i = 0; i < shiftSum; i++){
            int wv_length = wave2_frame[i].length;
            
            int[] fftSize = new int[shiftSum];
            int[] fftSize2 = new int[shiftSum];
            fftSize[i] = 1 << Le4MusicUtils.nextPow2(wave2_frame[i].length);
            fftSize2[2] = (fftSize[i] >> 1) + 1;
            
            double[][] src = new double[shiftSum][];
            src[i] = Arrays.stream(Arrays.copyOf(wave2_frame[i], fftSize[i]))
                           .map(w -> w / wv_length)
                           .toArray();
            Complex[][] spectrum = new Complex[shiftSum][];
            spectrum[i] = Le4MusicUtils.rfft(src[i]);
            
            double[][] specLog = new double[shiftSum][];
            specLog[i] = Arrays.stream(spectrum[i])
                            .mapToDouble(c -> 20.0 * Math.log10(c.abs()))
                            .toArray();
            
            int[] fftSize3 = new int[shiftSum];
            int[] fftSize4 = new int[shiftSum];
            fftSize3[i] = 1 << Le4MusicUtils.nextPow2(specLog[i].length);
            fftSize4[i] = (fftSize3[i] >> 1) + 1;
            double[][] src2 = new double[shiftSum][];
            src2[i] = Arrays.stream(Arrays.copyOf(specLog[i], fftSize3[i]))
                                    .map(w -> w)
                                    .toArray();
            //Complex[][] cepstrum = new Complex[shiftSum][];
            cepstrum[i] = Le4MusicUtils.rfft(src2[i]);
            
            for(int j = 0; j < cepstrum[i].length; j++){
                if(j >= 13) cepstrum[i][j] = Complex.ZERO;
            }
        }
        
        // 認識のテスト
        double[][] likelihood = new double[shiftSum][5];
        double[] rslt_vowel = new double[shiftSum];
        for(int n = 0; n < shiftSum; n++){
            Arrays.fill(likelihood[n], 0);
            for(int i = 0; i < 5; i++){
                for(int d = 0; d < 13; d++){
                    likelihood[n][i] = likelihood[n][i] - (1 / 2 * Math.log10(sigma2[i][d])
                                        + Math.pow(cepstrum[n][d].getReal() - mu[i][d], 2) / (2 * sigma2[i][d]));
                }
            }
            rslt_vowel[n] = argmax_arr(likelihood[n]);
        }
        
        
        /* データ系列を作成 */
        final ObservableList<XYChart.Data<Number, Number>> data =
                IntStream.range(0, rslt_vowel.length)
                    .mapToObj(i -> new XYChart.Data<Number, Number>(i / sampleRate * shiftSize, rslt_vowel[i]))
                    .collect(Collectors.toCollection(FXCollections::observableArrayList));
        
        /* データ系列に名前をつける */
        final XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName("Vowel");
        series.setData(data);
        
        /* 軸を作成 */
        final NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("Time (seconds)");
        final NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Vowel");
        
        /* チャートを作成 */
        final LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle("Vowel");
        chart.setCreateSymbols(false);
        chart.getData().add(series);
        
        /* グラフ描画 */
        final Scene scene = new Scene(chart, 800, 600);
        
        /* ウィンドウ表示 */
        primaryStage.setScene(scene);
        primaryStage.setTitle(getClass().getName());
        primaryStage.show();
    }
    
    public int argmax_arr(double[] arr){
        double max = arr[0];
        int argmax = 0;
        for(int i = 0; i < arr.length-1; i++){
            if(max <= arr[i+1]){
                max = arr[i+1]; argmax = i+1; 
            }
        }
        return argmax;
    }
}