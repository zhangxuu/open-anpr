package com.visual.open.anpr.core.models;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtSession;
import com.visual.open.anpr.core.base.BaseOnnxInfer;
import com.visual.open.anpr.core.base.PlateDetection;
import com.visual.open.anpr.core.domain.ImageMat;
import com.visual.open.anpr.core.domain.BorderMat;
import com.visual.open.anpr.core.domain.PlateInfo;
import com.visual.open.anpr.core.utils.ReleaseUtil;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.util.*;
import java.util.stream.Collectors;

public class TorchPlateDetection extends BaseOnnxInfer implements PlateDetection {
    private static int imageWidth = 640;
    private static int imageHeight= 640;
    private static  Scalar border = new Scalar(114, 114, 114);

    public TorchPlateDetection(String modelPath, int threads) {
        super(modelPath, threads);
    }

    @Override
    public List<PlateInfo> inference(ImageMat image, float scoreTh, float iouTh, Map<String, Object> params) {
        OnnxTensor tensor = null;
        OrtSession.Result output = null;
        BorderMat makeBorderMat = null;
        ImageMat imageMat = image.clone();
        try {
            //对图像进行标准宽高的处理
            makeBorderMat = resizeAndMakeBorderMat(imageMat.toCvMat(), imageWidth, imageHeight);
            //转换数据为张量
            tensor = ImageMat.fromCVMat(makeBorderMat.mat)
                    .blobFromImageAndDoReleaseMat(1.0/255, new Scalar(0, 0, 0), true)
                    .to4dFloatOnnxTensorAndNoReleaseMat(new float[]{1,1,1},true);
            //ONNX推理
            output = getSession().run(Collections.singletonMap(getInputName(), tensor));
            float[][][] result = (float[][][]) output.get(0).getValue();
            //候选框的处理
            List<float[]> boxes = filterCandidateBoxes(result[0], scoreTh, iouTh, params);
            //根据入模一起对图片的处理参数对box进行还原
            List<float[]> restoreBoxes = restoreBoxes(boxes, makeBorderMat);
            //模型后处理，转换为标准的结构化模型
            List<PlateInfo> plateInfos = new ArrayList<>();
            for (float[] item : restoreBoxes){
                //数据模型转换
                PlateInfo plateInfo = PlateInfo.build(item[4], PlateInfo.PlateBox.build(
                    PlateInfo.Point.build(
                        clip(item[5], 0, imageMat.getWidth()),
                        clip(item[6], 0, imageMat.getHeight())),
                    PlateInfo.Point.build(
                        clip(item[7], 0, imageMat.getWidth()),
                        clip(item[8], 0, imageMat.getHeight())),
                    PlateInfo.Point.build(
                        clip(item[9], 0, imageMat.getWidth()),
                        clip(item[10], 0, imageMat.getHeight())),
                    PlateInfo.Point.build(
                        clip(item[11], 0, imageMat.getWidth()),
                        clip(item[12], 0, imageMat.getHeight()))
                ));
                plateInfos.add(plateInfo);
            }
            //返回
            return plateInfos;
        }catch (Exception e){
            //抛出异常
            throw new RuntimeException(e);
        }finally {
            //释放资源
            if(null != tensor){
                ReleaseUtil.release(tensor);
            }
            if(null != output){
                ReleaseUtil.release(output);
            }
            if(null != makeBorderMat){
                ReleaseUtil.release(makeBorderMat);
            }
            if(null != imageMat){
                ReleaseUtil.release(imageMat);
            }
        }
    }

    /**
     * 候选框的处理
     * @param result    预测结果
     * @param scoreTh   候选框的分数阈值
     * @param iouTh     重叠比率
     * @param params    额外的参数
     * @return
     */
    private static List<float[]> filterCandidateBoxes(float[][] result, float scoreTh, float iouTh, Map<String, Object> params){
        //对预测的候选框进行预处理
        List<float[]> boxesForPretreatment = pretreatmentBoxes(result, scoreTh);
        //根据iou进行车牌框过滤
        List<float[]> boxesForNms = filterByNmsForIou(boxesForPretreatment, iouTh);
        //返回
        return boxesForNms;
    }


    /**
     * 对图像进行标准宽高的处理
     * @param image 原始图片
     * @param targetWidth   目标图片的宽度
     * @param targetHeight  目标图片的高度
     * @return
     */
    private static BorderMat resizeAndMakeBorderMat(Mat image, int targetWidth, int targetHeight){
        Mat resizeDst = null;
        try {
            int imageWidth = image.width();
            int imageHeight = image.height();
            float scaling = Math.min(1.0f * targetHeight / imageHeight, 1.0f * targetWidth / imageWidth);
            int newHeight  = Double.valueOf(imageHeight * scaling).intValue();
            int newWidth = Double.valueOf(imageWidth * scaling).intValue();
            int topOffset = Double.valueOf((targetHeight - newHeight ) / 2.0).intValue();
            int leftOffset = Double.valueOf((targetWidth-newWidth) / 2.0).intValue();
            int bottomOffset = targetHeight - newHeight -topOffset ;
            int rightOffset = targetWidth - newWidth-leftOffset ;
            resizeDst = new Mat();
            Imgproc.resize(image, resizeDst, new Size(newWidth,newHeight ), 0, 0, Imgproc.INTER_AREA);
            Mat res = new Mat();
            Core.copyMakeBorder(resizeDst, res, topOffset, bottomOffset, leftOffset, rightOffset, Core.BORDER_CONSTANT, border);
            return new BorderMat(res, scaling, topOffset, bottomOffset, leftOffset, rightOffset);
        }finally {
            ReleaseUtil.release(resizeDst);
        }
    }

    /**
     * 对预测的候选框进行预处理
     * @param result  模型预测的候选框
     * @param scoreThresh   候选框的分数阈值
     * @return  处理后的待选框
     */
    private static List<float[]> pretreatmentBoxes(float[][] result, float scoreThresh){
        return
                Arrays.stream(result)
                        .filter(item -> item[4] > scoreThresh)
                        .map(item -> {
                            float[] temp = new float[14];
                            //计算分数
                            item[13] = item[13] * item[4];
                            item[14] = item[14] * item[4];
                            //计算坐标
                            temp[0] = item[0] - item[2] / 2;
                            temp[1] = item[1] - item[3] / 2;
                            temp[2] = item[0] + item[2] / 2;
                            temp[3] = item[1] + item[3] / 2;
                            //计算车牌的预测分数
                            temp[4] = Math.max(item[13], item[14]);
                            //标记点数据
                            temp[5] = item[5];
                            temp[6] = item[6];
                            temp[7] = item[7];
                            temp[8] = item[8];
                            temp[9] = item[9];
                            temp[10] = item[10];
                            temp[11] = item[11];
                            temp[12] = item[12];
                            //计算是双层还是单层车牌
                            temp[13] = item[13] >= item[14] ? 0 : 1;
                            return temp;
                        })
                        .sorted((a, b) -> Float.compare(b[4], a[4]))
                        .collect(Collectors.toList());
    }

    /**
     * 根据iou进行车牌框过滤
     * @param boxes 待处理的boxes
     * @param iouTh 重叠比率
     * @return  过滤后的车牌坐标
     */
    private static List<float[]> filterByNmsForIou(List<float[]>boxes, float iouTh){
        List<float[]> result = new ArrayList<>();
        while(!boxes.isEmpty()){
            Iterator<float[]> iterator = boxes.iterator();
            //获取第一个元素，并删除元素
            float[] firstFace = iterator.next();
            iterator.remove();
            //对比后面元素与第一个元素之间的iou
            while (iterator.hasNext()) {
                float[] nextFace = iterator.next();
                float x1=Math.max(firstFace[0], nextFace[0]);
                float y1=Math.max(firstFace[1], nextFace[1]);
                float x2=Math.min(firstFace[2], nextFace[2]);
                float y2=Math.min(firstFace[3], nextFace[3]);
                float w = Math.max(0, x2-x1);
                float h = Math.max(0, y2-y1);
                float inter_area = w * h;
                float union_area = (firstFace[2] - firstFace[0]) * (firstFace[3] - firstFace[1]) +
                        (nextFace[2] - nextFace[0]) * (nextFace[3] - nextFace[1]);
                float iou = inter_area/(union_area-inter_area);
                if(iou >= iouTh){
                    iterator.remove();
                }
            }
            result.add(firstFace);
        }
        return result;
    }

    /**
     * 根据入模一起对图片的处理参数对box进行还原
     * @param boxes     候选框
     * @param border    边框及缩放信息
     * @return
     */
    private static List<float[]> restoreBoxes(List<float[]>boxes, BorderMat border){
        return boxes.stream().peek(item -> {
            item[0]  = (item[0]  - border.left) / border.scale;
            item[2]  = (item[2]  - border.left) / border.scale;
            item[5]  = (item[5]  - border.left) / border.scale;
            item[7]  = (item[7]  - border.left) / border.scale;
            item[9]  = (item[9]  - border.left) / border.scale;
            item[11] = (item[11] - border.left) / border.scale;

            item[1]  = (item[1]  - border.top) / border.scale;
            item[3]  = (item[3]  - border.top) / border.scale;
            item[6]  = (item[6]  - border.top) / border.scale;
            item[8]  = (item[8]  - border.top) / border.scale;
            item[10] = (item[10] - border.top) / border.scale;
            item[12] = (item[12] - border.top) / border.scale;
        }).collect(Collectors.toList());
    }

    /**
     * 边框数据清洗
     * @param value
     * @param min
     * @param max
     * @return
     */
    private static int clip(double value, int min, int max){
        if(value > max){
            return max;
        }
        if(value < min){
            return min;
        }
        return Double.valueOf(value).intValue();
    }
}