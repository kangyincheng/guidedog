YOLOv8-Tiny 离线识物模型说明
================================

本目录用于存放 YOLOv8-Tiny ONNX 模型文件。

模型文件名必须为：yolov8_tiny.onnx

获取模型步骤：
1. 安装 Python 环境和 ultralytics 库：
   pip install ultralytics onnx

2. 下载 YOLOv8-Tiny 预训练权重并导出为 ONNX 格式：

   from ultralytics import YOLO
   model = YOLO("yolov8n.pt")  # nano版本，最轻量
   model.export(format="onnx", imgsz=640, simplify=True)

   或使用 tiny 版本（如有）：
   model = YOLO("yolov8t.pt")
   model.export(format="onnx", imgsz=640, simplify=True)

3. 将导出的 yolov8n.onnx（或 yolov8t.onnx）重命名为 yolov8_tiny.onnx

4. 将 yolov8_tiny.onnx 复制到此目录：
   app/src/main/assets/yolov8_tiny.onnx

模型规格：
- 输入尺寸：640x640 (NCHW格式)
- 输入通道数：3 (RGB)
- 归一化范围：0-1 (像素值/255)
- 输出格式：[1, 84, 8400]
  - 84 = 4(bbox: x1,y1,x2,y2) + 80(COCO类别置信度)
  - 8400 = anchor数量
- 支持类别：COCO 80类（行人、车辆、自行车等）

危险障碍物适配：
- 3级(最高危险)：汽车、卡车、公交车、摩托车、熊
- 2级(中等危险)：自行车、红绿灯、消防栓、停车标志、狗、马、牛
- 1级(一般障碍)：行人、椅子、桌子、床、沙发、长椅、行李箱等

距离估算：
- 根据检测框面积占画面比例动态调整危险等级
- 面积占比 >25%：危险等级 +1（近距离）
- 面积占比 10%-25%：保持原等级（中距离）
- 面积占比 <10%：危险等级 -1（远距离）

方位判定：
- 检测框中心X坐标 < 画面宽度1/3：左前方
- 检测框中心X坐标 > 画面宽度2/3：右前方
- 其他：正前方
