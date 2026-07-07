/**
 * YoloOnnxEngine.kt - YOLO ONNX 推理引擎
 * 封装 ONNX Runtime，加载 .onnx 模型，执行推理和后处理
 * 支持分割模型（盲道、斑马线）和检测模型（障碍物、红绿灯）
 */
package com.blindnav.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.blindnav.app.data.BoundingBox
import com.blindnav.app.data.DetectionResult
import com.blindnav.app.data.Point
import com.blindnav.app.data.SegmentationMask
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import java.io.File
import java.nio.FloatBuffer
import java.util.Collections

/**
 * YOLO ONNX 推理引擎
 * 支持 YOLOv8-seg 分割模型和 YOLO 检测模型
 */
class YoloOnnxEngine(private val context: Context) {

    companion object {
        private const val TAG = "YoloOnnxEngine"
        private const val INPUT_SIZE = 640  // 必须与模型导出尺寸一致 (convert_models.py 使用 640)
        private const val CONF_THRESHOLD = 0.10f  // 低阈值减少漏检，后续 BlindPathDetector 有二次过滤
        private const val IOU_THRESHOLD = 0.45f
        private const val MASK_THRESHOLD = 0.5f

        // COCO 80 类名称
        val COCO_CLASSES = arrayOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
            "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
            "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
            "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush"
        )

        // OIV7 601 类（YOLO11s-oiv7 模型，Open Images V7 数据集）
        val SHOPPING_CLASSES = arrayOf(
            "Accordion", "Adhesive tape", "Aircraft", "Airplane", "Alarm clock", "Alpaca",
            "Ambulance", "Animal", "Ant", "Antelope", "Apple", "Armadillo",
            "Artichoke", "Auto part", "Axe", "Backpack", "Bagel", "Baked goods",
            "Balance beam", "Ball", "Balloon", "Banana", "Band-aid", "Banjo",
            "Barge", "Barrel", "Baseball bat", "Baseball glove", "Bat (Animal)", "Bathroom accessory",
            "Bathroom cabinet", "Bathtub", "Beaker", "Bear", "Bed", "Bee",
            "Beehive", "Beer", "Beetle", "Bell pepper", "Belt", "Bench",
            "Bicycle", "Bicycle helmet", "Bicycle wheel", "Bidet", "Billboard", "Billiard table",
            "Binoculars", "Bird", "Blender", "Blue jay", "Boat", "Bomb",
            "Book", "Bookcase", "Boot", "Bottle", "Bottle opener", "Bow and arrow",
            "Bowl", "Bowling equipment", "Box", "Boy", "Brassiere", "Bread",
            "Briefcase", "Broccoli", "Bronze sculpture", "Brown bear", "Building", "Bull",
            "Burrito", "Bus", "Bust", "Butterfly", "Cabbage", "Cabinetry",
            "Cake", "Cake stand", "Calculator", "Camel", "Camera", "Can opener",
            "Canary", "Candle", "Candy", "Cannon", "Canoe", "Cantaloupe",
            "Car", "Carnivore", "Carrot", "Cart", "Cassette deck", "Castle",
            "Cat", "Cat furniture", "Caterpillar", "Cattle", "Ceiling fan", "Cello",
            "Centipede", "Chainsaw", "Chair", "Cheese", "Cheetah", "Chest of drawers",
            "Chicken", "Chime", "Chisel", "Chopsticks", "Christmas tree", "Clock",
            "Closet", "Clothing", "Coat", "Cocktail", "Cocktail shaker", "Coconut",
            "Coffee", "Coffee cup", "Coffee table", "Coffeemaker", "Coin", "Common fig",
            "Common sunflower", "Computer keyboard", "Computer monitor", "Computer mouse", "Container", "Convenience store",
            "Cookie", "Cooking spray", "Corded phone", "Cosmetics", "Couch", "Countertop",
            "Cowboy hat", "Crab", "Cream", "Cricket ball", "Crocodile", "Croissant",
            "Crown", "Crutch", "Cucumber", "Cupboard", "Curtain", "Cutting board",
            "Dagger", "Dairy Product", "Deer", "Desk", "Dessert", "Diaper",
            "Dice", "Digital clock", "Dinosaur", "Dishwasher", "Dog", "Dog bed",
            "Doll", "Dolphin", "Door", "Door handle", "Doughnut", "Dragonfly",
            "Drawer", "Dress", "Drill (Tool)", "Drink", "Drinking straw", "Drum",
            "Duck", "Dumbbell", "Eagle", "Earrings", "Egg (Food)", "Elephant",
            "Envelope", "Eraser", "Face powder", "Facial tissue holder", "Falcon", "Fashion accessory",
            "Fast food", "Fax", "Fedora", "Filing cabinet", "Fire hydrant", "Fireplace",
            "Fish", "Flag", "Flashlight", "Flower", "Flowerpot", "Flute",
            "Flying disc", "Food", "Food processor", "Football", "Football helmet", "Footwear",
            "Fork", "Fountain", "Fox", "French fries", "French horn", "Frog",
            "Fruit", "Frying pan", "Furniture", "Garden Asparagus", "Gas stove", "Giraffe",
            "Girl", "Glasses", "Glove", "Goat", "Goggles", "Goldfish",
            "Golf ball", "Golf cart", "Gondola", "Goose", "Grape", "Grapefruit",
            "Grinder", "Guacamole", "Guitar", "Hair dryer", "Hair spray", "Hamburger",
            "Hammer", "Hamster", "Hand dryer", "Handbag", "Handgun", "Harbor seal",
            "Harmonica", "Harp", "Harpsichord", "Hat", "Headphones", "Heater",
            "Hedgehog", "Helicopter", "Helmet", "High heels", "Hiking equipment", "Hippopotamus",
            "Home appliance", "Honeycomb", "Horizontal bar", "Horse", "Hot dog", "House",
            "Houseplant", "Human arm", "Human beard", "Human body", "Human ear", "Human eye",
            "Human face", "Human foot", "Human hair", "Human hand", "Human head", "Human leg",
            "Human mouth", "Human nose", "Humidifier", "Ice cream", "Indoor rower", "Infant bed",
            "Insect", "Invertebrate", "Ipod", "Isopod", "Jacket", "Jacuzzi",
            "Jaguar (Animal)", "Jeans", "Jellyfish", "Jet ski", "Jug", "Juice",
            "Kangaroo", "Kettle", "Kitchen & dining room table", "Kitchen appliance", "Kitchen knife", "Kitchen utensil",
            "Kitchenware", "Kite", "Knife", "Koala", "Ladder", "Ladle",
            "Ladybug", "Lamp", "Land vehicle", "Lantern", "Laptop", "Lavender (Plant)",
            "Lemon", "Leopard", "Light bulb", "Light switch", "Lighthouse", "Lily",
            "Limousine", "Lion", "Lipstick", "Lizard", "Lobster", "Loveseat",
            "Luggage and bags", "Lynx", "Magpie", "Mammal", "Man", "Mango",
            "Maple", "Maracas", "Marine invertebrates", "Marine mammal", "Measuring cup", "Mechanical fan",
            "Medical equipment", "Microphone", "Microwave oven", "Milk", "Miniskirt", "Mirror",
            "Missile", "Mixer", "Mixing bowl", "Mobile phone", "Monkey", "Moths and butterflies",
            "Motorcycle", "Mouse", "Muffin", "Mug", "Mule", "Mushroom",
            "Musical instrument", "Musical keyboard", "Nail (Construction)", "Necklace", "Nightstand", "Oboe",
            "Office building", "Office supplies", "Orange", "Organ (Musical Instrument)", "Ostrich", "Otter",
            "Oven", "Owl", "Oyster", "Paddle", "Palm tree", "Pancake",
            "Panda", "Paper cutter", "Paper towel", "Parachute", "Parking meter", "Parrot",
            "Pasta", "Pastry", "Peach", "Pear", "Pen", "Pencil case",
            "Pencil sharpener", "Penguin", "Perfume", "Person", "Personal care", "Personal flotation device",
            "Piano", "Picnic basket", "Picture frame", "Pig", "Pillow", "Pineapple",
            "Pitcher (Container)", "Pizza", "Pizza cutter", "Plant", "Plastic bag", "Plate",
            "Platter", "Plumbing fixture", "Polar bear", "Pomegranate", "Popcorn", "Porch",
            "Porcupine", "Poster", "Potato", "Power plugs and sockets", "Pressure cooker", "Pretzel",
            "Printer", "Pumpkin", "Punching bag", "Rabbit", "Raccoon", "Racket",
            "Radish", "Ratchet (Device)", "Raven", "Rays and skates", "Red panda", "Refrigerator",
            "Remote control", "Reptile", "Rhinoceros", "Rifle", "Ring binder", "Rocket",
            "Roller skates", "Rose", "Rugby ball", "Ruler", "Salad", "Salt and pepper shakers",
            "Sandal", "Sandwich", "Saucer", "Saxophone", "Scale", "Scarf",
            "Scissors", "Scoreboard", "Scorpion", "Screwdriver", "Sculpture", "Sea lion",
            "Sea turtle", "Seafood", "Seahorse", "Seat belt", "Segway", "Serving tray",
            "Sewing machine", "Shark", "Sheep", "Shelf", "Shellfish", "Shirt",
            "Shorts", "Shotgun", "Shower", "Shrimp", "Sink", "Skateboard",
            "Ski", "Skirt", "Skull", "Skunk", "Skyscraper", "Slow cooker",
            "Snack", "Snail", "Snake", "Snowboard", "Snowman", "Snowmobile",
            "Snowplow", "Soap dispenser", "Sock", "Sofa bed", "Sombrero", "Sparrow",
            "Spatula", "Spice rack", "Spider", "Spoon", "Sports equipment", "Sports uniform",
            "Squash (Plant)", "Squid", "Squirrel", "Stairs", "Stapler", "Starfish",
            "Stationary bicycle", "Stethoscope", "Stool", "Stop sign", "Strawberry", "Street light",
            "Stretcher", "Studio couch", "Submarine", "Submarine sandwich", "Suit", "Suitcase",
            "Sun hat", "Sunglasses", "Surfboard", "Sushi", "Swan", "Swim cap",
            "Swimming pool", "Swimwear", "Sword", "Syringe", "Table", "Table tennis racket",
            "Tablet computer", "Tableware", "Taco", "Tank", "Tap", "Tart",
            "Taxi", "Tea", "Teapot", "Teddy bear", "Telephone", "Television",
            "Tennis ball", "Tennis racket", "Tent", "Tiara", "Tick", "Tie",
            "Tiger", "Tin can", "Tire", "Toaster", "Toilet", "Toilet paper",
            "Tomato", "Tool", "Toothbrush", "Torch", "Tortoise", "Towel",
            "Tower", "Toy", "Traffic light", "Traffic sign", "Train", "Training bench",
            "Treadmill", "Tree", "Tree house", "Tripod", "Trombone", "Trousers",
            "Truck", "Trumpet", "Turkey", "Turtle", "Umbrella", "Unicycle",
            "Van", "Vase", "Vegetable", "Vehicle", "Vehicle registration plate", "Violin",
            "Volleyball (Ball)", "Waffle", "Waffle iron", "Wall clock", "Wardrobe", "Washing machine",
            "Waste container", "Watch", "Watercraft", "Watermelon", "Weapon", "Whale",
            "Wheel", "Wheelchair", "Whisk", "Whiteboard", "Willow", "Window",
            "Window blind", "Wine", "Wine glass", "Wine rack", "Winter melon", "Wok",
            "Woman", "Wood-burning stove", "Woodpecker", "Worm", "Wrench", "Zebra",
            "Zucchini"
        )

        // 红绿灯模型类别名（trafficlight 模型，7 个类）
        val TRAFFIC_CLASSES = arrayOf(
            "blank",             // class 0: 空白
            "countdown_blank",   // class 1: 倒计时空白
            "countdown_go",      // class 2: 绿灯倒计时（黄灯）
            "countdown_stop",    // class 3: 红灯倒计时
            "crossing",          // class 4: 行人过街
            "go",                // class 5: 绿灯
            "stop"               // class 6: 红灯
        )

        // 人行红绿灯分类模型（ResNet18, 448x448, 5 类）
        val CLA_CLASSES = arrayOf(
            "red",                // class 0: 红灯
            "green",              // class 1: 绿灯
            "countdown_green",    // class 2: 倒计时绿灯
            "countdown_blank",    // class 3: 倒计时空白
            "none"                // class 4: 无灯
        )

        val CLA_CLASSES_CN = arrayOf("红灯", "绿灯", "黄灯", "黄灯", "无灯")

        // 分类模型参数
        private const val CLA_INPUT_SIZE = 448
        // ImageNet 标准化参数（与训练时一致）
        private const val MEAN_R = 0.485f; private const val MEAN_G = 0.456f; private const val MEAN_B = 0.406f
        private const val STD_R  = 0.229f; private const val STD_G  = 0.224f; private const val STD_B  = 0.225f

        // LYTNetV2 模型参数（768×576, 无归一化）
        const val LYT_INPUT_H = 768
        const val LYT_INPUT_W = 576
    }

    // ONNX Runtime 环境和会话
    private val ortEnvironment = OrtEnvironment.getEnvironment()
    private var segSession: OrtSession? = null
    private var detectSession: OrtSession? = null
    private var trafficSession: OrtSession? = null
    private var shoppingSession: OrtSession? = null
    private var claSession: OrtSession? = null       // 分类模型会话
    private var lytSession: OrtSession? = null       // LYTNetV2 模型会话

    // 模型是否已加载
    private var segModelLoaded = false
    private var detectModelLoaded = false
    private var trafficModelLoaded = false
    private var shoppingModelLoaded = false
    private var claModelLoaded = false               // 分类模型是否已加载
    private var lytModelLoaded = false               // LYTNetV2 是否已加载

    /**
     * 将 assets 中的模型文件复制到缓存目录（避免 readBytes() 导致 OOM）
     * 增加长度校验：asset 更新后若缓存大小不一致，则重新复制，防止旧缓存导致模型版本错乱
     */
    private fun copyAssetToCache(assetPath: String): File? {
        return try {
            val cacheFile = File(context.cacheDir, File(assetPath).name)
            val assetFd = context.assets.openFd(assetPath)
            val assetLen = assetFd.length
            assetFd.close()

            if (cacheFile.exists() && cacheFile.length() == assetLen) {
                return cacheFile
            }

            context.assets.open(assetPath).use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            cacheFile
        } catch (e: Exception) {
            Log.e(TAG, "复制模型文件失败: $assetPath", e)
            null
        }
    }

    /**
     * 加载分割模型（用于盲道和斑马线分割）
     * 模型文件路径: assets/models/xxx.onnx
     * 使用文件方式加载，避免 readBytes() 导致 OOM
     *
     * 注意：FP16 量化模型在某些设备的 NNAPI 上可能加载失败，因此先尝试 NNAPI，
     * 失败后再回退到纯 CPU，确保分割模型可用。
     */
    fun loadSegModel(modelFileName: String): Boolean {
        val cacheFile = copyAssetToCache("models/$modelFileName") ?: return false

        // FP16 量化模型：Resize 算子保留 FP32（量化时 op_block_list 包含 Resize），
        // 其余算子使用 FP16。优先尝试 NNAPI，失败则回退 CPU。
        val t0 = System.currentTimeMillis()
        return try {
            val options = createSessionOptions()  // NNAPI 优先
            segSession = ortEnvironment.createSession(cacheFile.absolutePath, options)
            segModelLoaded = true
            val provider = "NNAPI"
            val elapsed = System.currentTimeMillis() - t0
            Log.w(TAG, "=== 分割模型加载成功(NNAPI): $modelFileName (${cacheFile.length() / 1024 / 1024}MB) 耗时=${elapsed}ms ===")
            true
        } catch (e: Exception) {
            Log.w(TAG, "NNAPI 加载分割模型失败，回退到 CPU: ${e.message}")
            try {
                val cpuOptions = createSessionOptions(useNnapi = false)
                segSession = ortEnvironment.createSession(cacheFile.absolutePath, cpuOptions)
                segModelLoaded = true
                val provider = "CPU"
                val elapsed = System.currentTimeMillis() - t0
                Log.w(TAG, "=== 分割模型加载成功(CPU回退): $modelFileName (${cacheFile.length() / 1024 / 1024}MB) 耗时=${elapsed}ms ===")
                true
            } catch (e2: Exception) {
                Log.e(TAG, "CPU 回退也失败: $modelFileName", e2)
                segModelLoaded = false
                false
            } catch (e2: Exception) {
                Log.e(TAG, "CPU 回退也失败: $modelFileName", e2)
                segModelLoaded = false
                false
            }
        }
    }

    /**
     * 加载检测模型（用于障碍物检测）
     * 使用文件方式加载，避免 readBytes() 导致 OOM
     */
    fun loadDetectModel(modelFileName: String): Boolean {
        return try {
            val cacheFile = copyAssetToCache("models/$modelFileName") ?: return false
            val options = createSessionOptions()
            detectSession = ortEnvironment.createSession(cacheFile.absolutePath, options)
            detectModelLoaded = true
            Log.i(TAG, "检测模型加载成功: $modelFileName (${cacheFile.length() / 1024 / 1024}MB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "检测模型加载失败: $modelFileName", e)
            detectModelLoaded = false
            false
        }
    }

    /**
     * 加载红绿灯专用模型
     * 使用文件方式加载，避免 readBytes() 导致 OOM
     */
    fun loadTrafficModel(modelFileName: String): Boolean {
        return try {
            val cacheFile = copyAssetToCache("models/$modelFileName") ?: return false
            val options = createSessionOptions()
            trafficSession = ortEnvironment.createSession(cacheFile.absolutePath, options)
            trafficModelLoaded = true
            Log.i(TAG, "红绿灯模型加载成功: $modelFileName (${cacheFile.length() / 1024 / 1024}MB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "红绿灯模型加载失败: $modelFileName", e)
            trafficModelLoaded = false
            false
        }
    }

    /**
     * 检查红绿灯模型是否已加载
     */
    fun isTrafficModelLoaded(): Boolean = trafficModelLoaded

    /**
     * 加载商品识别专用模型
     */
    fun loadShoppingModel(modelFileName: String): Boolean {
        return try {
            val cacheFile = copyAssetToCache("models/$modelFileName") ?: return false
            val options = createSessionOptions()
            shoppingSession = ortEnvironment.createSession(cacheFile.absolutePath, options)
            shoppingModelLoaded = true
            Log.i(TAG, "商品模型加载成功: $modelFileName (${cacheFile.length() / 1024 / 1024}MB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "商品模型加载失败: $modelFileName", e)
            shoppingModelLoaded = false
            false
        }
    }

    /**
     * 检查商品模型是否已加载
     */
    fun isShoppingModelLoaded(): Boolean = shoppingModelLoaded

    /**
     * 检查检测模型是否已加载
     */
    fun isDetectModelLoaded(): Boolean = detectModelLoaded

    // ============ 分类模型（ResNet18, 人行红绿灯） ============

    /**
     * 加载人行红绿灯分类模型
     */
    fun loadClaModel(modelFileName: String): Boolean {
        return try {
            val cacheFile = copyAssetToCache("models/$modelFileName") ?: return false
            val options = createSessionOptions()
            claSession = ortEnvironment.createSession(cacheFile.absolutePath, options)
            claModelLoaded = true
            Log.i(TAG, "分类模型加载成功: $modelFileName (${cacheFile.length() / 1024 / 1024}MB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "分类模型加载失败: $modelFileName", e)
            claModelLoaded = false
            false
        }
    }

    fun isClaModelLoaded(): Boolean = claModelLoaded

    /**
     * 执行人行红绿灯分类推理
     * @return Pair<classIndex, confidence> 或 null
     */
    fun runClassification(bitmap: Bitmap): Pair<Int, Float>? {
        if (!claModelLoaded || claSession == null) {
            Log.w(TAG, "分类模型未加载")
            return null
        }
        return try {
            val inputTensor = preprocessClassification(bitmap)
            val inputName = claSession!!.inputNames.first()
            val inputs = Collections.singletonMap(inputName, inputTensor)
            val outputs = claSession!!.run(inputs)
            val outputTensor = outputs[0]
            val scores = (outputTensor.value as Array<FloatArray>)[0]

            // softmax
            var maxScore = Float.NEGATIVE_INFINITY
            for (s in scores) { if (s > maxScore) maxScore = s }
            var sumExp = 0f
            for (s in scores) { sumExp += Math.exp((s - maxScore).toDouble()).toFloat() }

            var bestIdx = 0
            var bestConf = 0f
            for (i in scores.indices) {
                val prob = Math.exp((scores[i] - maxScore).toDouble()).toFloat() / sumExp
                if (prob > bestConf) {
                    bestConf = prob
                    bestIdx = i
                }
            }

            inputTensor.close()
            Log.d(TAG, "runClassification: class=$bestIdx(${CLA_CLASSES[bestIdx]}) conf=${String.format("%.3f", bestConf)}")
            Pair(bestIdx, bestConf)
        } catch (e: Exception) {
            Log.e(TAG, "分类推理失败", e)
            null
        }
    }

    // 分类模型预处理缓冲区
    private val claPixels = IntArray(CLA_INPUT_SIZE * CLA_INPUT_SIZE)
    private val claFloats = FloatArray(1 * 3 * CLA_INPUT_SIZE * CLA_INPUT_SIZE)

    /**
     * 分类模型预处理：直接缩放到 448x448 + ImageNet 标准化 + NCHW
     * 与训练时的 transforms.Resize((448,448)) + Normalize 一致
     */
    private fun preprocessClassification(bitmap: Bitmap): OnnxTensor {
        // 直接缩放到 448x448
        val resized = Bitmap.createScaledBitmap(bitmap, CLA_INPUT_SIZE, CLA_INPUT_SIZE, true)
        resized.getPixels(claPixels, 0, CLA_INPUT_SIZE, 0, 0, CLA_INPUT_SIZE, CLA_INPUT_SIZE)
        if (resized != bitmap) resized.recycle()

        val sz = CLA_INPUT_SIZE
        for (i in claPixels.indices) {
            val pixel = claPixels[i]
            // 先归一化到 [0,1]，再 ImageNet 标准化
            val r = (((pixel shr 16) and 0xFF) / 255.0f - MEAN_R) / STD_R
            val g = (((pixel shr 8) and 0xFF) / 255.0f - MEAN_G) / STD_G
            val b = ((pixel and 0xFF) / 255.0f - MEAN_B) / STD_B
            claFloats[i] = r               // CHW: channel 0 = R
            claFloats[i + sz * sz] = g     // channel 1 = G
            claFloats[i + 2 * sz * sz] = b // channel 2 = B
        }

        val shape = longArrayOf(1, 3, CLA_INPUT_SIZE.toLong(), CLA_INPUT_SIZE.toLong())
        return OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(claFloats), shape)
    }

    // ============ LYTNetV2 分类模型（768×576, 原始像素）============

    /**
     * 加载 LYTNetV2 人行红绿灯分类模型
     */
    fun loadLytModel(modelFileName: String): Boolean {
        return try {
            val cacheFile = copyAssetToCache("models/$modelFileName") ?: return false
            val options = createSessionOptions()
            lytSession = ortEnvironment.createSession(cacheFile.absolutePath, options)
            lytModelLoaded = true
            Log.i(TAG, "LYTNetV2 模型加载成功: $modelFileName (${cacheFile.length() / 1024 / 1024}MB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "LYTNetV2 模型加载失败: $modelFileName", e)
            lytModelLoaded = false
            false
        }
    }

    fun isLytModelLoaded(): Boolean = lytModelLoaded

    /**
     * 执行 LYTNetV2 分类推理（768×576, 原始像素无归一化）
     * @return Pair<classIndex, confidence> 或 null
     */
    fun runLytClassification(bitmap: Bitmap): Pair<Int, Float>? {
        if (!lytModelLoaded || lytSession == null) {
            Log.w(TAG, "LYTNetV2 模型未加载")
            return null
        }
        return try {
            val inputTensor = preprocessLytClassification(bitmap)
            val inputName = lytSession!!.inputNames.first()
            val inputs = Collections.singletonMap(inputName, inputTensor)
            val outputs = lytSession!!.run(inputs)
            val outputTensor = outputs[0]
            val scores = (outputTensor.value as Array<FloatArray>)[0]

            var bestIdx = 0
            var bestProb = 0f
            for (i in scores.indices) {
                if (scores[i] > bestProb) {
                    bestProb = scores[i]
                    bestIdx = i
                }
            }

            inputTensor.close()
            Log.d(TAG, "runLytClassification: class=$bestIdx(${CLA_CLASSES[bestIdx]}) prob=${String.format("%.3f", bestProb)}")
            Pair(bestIdx, bestProb)
        } catch (e: Exception) {
            Log.e(TAG, "LYTNetV2 推理失败", e)
            null
        }
    }

    // LYTNetV2 预处理缓冲区
    private val lytPixels = IntArray(LYT_INPUT_H * LYT_INPUT_W)
    private val lytFloats = FloatArray(1 * 3 * LYT_INPUT_H * LYT_INPUT_W)

    /**
     * LYTNetV2 预处理：缩放到 768×576，原始像素 [0, 255] → float, CHW 格式
     * 不进行任何归一化（训练时未使用 ImageNet normalization）
     */
    private fun preprocessLytClassification(bitmap: Bitmap): OnnxTensor {
        val resized = Bitmap.createScaledBitmap(bitmap, LYT_INPUT_W, LYT_INPUT_H, true)
        resized.getPixels(lytPixels, 0, LYT_INPUT_W, 0, 0, LYT_INPUT_W, LYT_INPUT_H)
        if (resized != bitmap) resized.recycle()

        val hw = LYT_INPUT_H * LYT_INPUT_W
        for (i in lytPixels.indices) {
            val pixel = lytPixels[i]
            lytFloats[i] = ((pixel shr 16) and 0xFF).toFloat()                    // R
            lytFloats[i + hw] = ((pixel shr 8) and 0xFF).toFloat()                // G
            lytFloats[i + 2 * hw] = (pixel and 0xFF).toFloat()                    // B
        }

        val shape = longArrayOf(1, 3, LYT_INPUT_H.toLong(), LYT_INPUT_W.toLong())
        return OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(lytFloats), shape)
    }

    /**
     * 创建 ONNX SessionOptions
     *
     * NNAPI 会将推理路由到设备的 NPU/DSP/GPU，通常比纯 CPU 快 3-5 倍。
     * 但部分设备/驱动对 FP16 量化模型支持不佳，可能导致加载或初始化失败。
     * 因此提供 useNnapi 参数，让调用方在 NNAPI 失败时回退到 CPU。
     *
     * @param useNnapi 是否尝试启用 NNAPI 硬件加速
     */
    private fun createSessionOptions(useNnapi: Boolean = true): SessionOptions {
        return SessionOptions().apply {
            setOptimizationLevel(SessionOptions.OptLevel.ALL_OPT)
            if (useNnapi) {
                try {
                    addNnapi()
                    Log.i(TAG, "NNAPI 硬件加速已启用")
                } catch (e: Exception) {
                    Log.w(TAG, "NNAPI 不可用，回退到 CPU: ${e.message}")
                }
            } else {
                Log.i(TAG, "使用 CPU 推理")
            }
        }
    }

    /**
     * 执行商品检测推理
     * 使用专用的 shopping.onnx 模型
     */
    fun runShoppingDetection(bitmap: Bitmap): List<DetectionResult> {
        if (!shoppingModelLoaded || shoppingSession == null) {
            Log.w(TAG, "商品模型未加载，返回空结果")
            return emptyList()
        }

        return try {
            val (inputTensor, originalWidth, originalHeight) = preprocessImage(bitmap)
            val results = runInference(shoppingSession!!, inputTensor, false,
                originalWidth, originalHeight, SHOPPING_CLASSES)
            inputTensor.close()
            results
        } catch (e: Exception) {
            Log.e(TAG, "商品推理失败", e)
            emptyList()
        }
    }

    /**
     * 执行红绿灯检测推理
     * 使用专用的 trafficlight.onnx 模型
     * 类别: stop(红灯), go(绿灯), countdown_go(黄灯/倒计时)
     */
    fun runTrafficDetection(bitmap: Bitmap): List<DetectionResult> {
        if (!trafficModelLoaded || trafficSession == null) {
            Log.w(TAG, "红绿灯模型未加载，返回空结果")
            return emptyList()
        }

        return try {
            val (inputTensor, originalWidth, originalHeight) = preprocessImage(bitmap)
            // 红绿灯模型使用更低的阈值（0.05），捕获更多检测候选
            val results = runInference(trafficSession!!, inputTensor, false,
                originalWidth, originalHeight, TRAFFIC_CLASSES, 0.05f)
            inputTensor.close()
            results
        } catch (e: Exception) {
            Log.e(TAG, "红绿灯推理失败", e)
            emptyList()
        }
    }

    /**
     * 执行分割推理
     * 输入: Bitmap 图像
     * 输出: 检测结果列表（包含分割掩码）
     */
    fun runSegmentation(bitmap: Bitmap): List<DetectionResult> {
        if (!segModelLoaded || segSession == null) {
            Log.w(TAG, "分割模型未加载，返回空结果")
            return emptyList()
        }

        return try {
            val (inputTensor, originalWidth, originalHeight) = preprocessImage(bitmap)
            val results = runInference(segSession!!, inputTensor, true, originalWidth, originalHeight)
            inputTensor.close()
            results
        } catch (e: Exception) {
            Log.e(TAG, "分割推理失败", e)
            emptyList()
        }
    }

    /**
     * 执行检测推理
     * 输入: Bitmap 图像
     * 输出: 检测结果列表（不含掩码）
     */
    fun runDetection(bitmap: Bitmap): List<DetectionResult> {
        if (!detectModelLoaded || detectSession == null) {
            Log.w(TAG, "检测模型未加载，返回空结果")
            return emptyList()
        }

        return try {
            val (inputTensor, originalWidth, originalHeight) = preprocessImage(bitmap)
            val results = runInference(detectSession!!, inputTensor, false, originalWidth, originalHeight)
            inputTensor.close()
            results
        } catch (e: Exception) {
            Log.e(TAG, "检测推理失败", e)
            emptyList()
        }
    }

    // Letterbox 参数（当前帧）
    private var letterboxScale = 1f
    private var letterboxPadX = 0f
    private var letterboxPadY = 0f

    // ===== 预处理缓冲区复用（避免每帧 ~8MB 堆分配，减少 GC 压力） =====
    private val reusablePixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val reusableFloats = FloatArray(1 * 3 * INPUT_SIZE * INPUT_SIZE)
    private val reusablePadded = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    private val reusableCanvas = android.graphics.Canvas(reusablePadded)

    /**
     * 图像预处理：Letterbox 缩放 + 归一化 + 转 NCHW 格式
     * 与 YOLOv8 训练时的预处理保持一致（等比缩放 + 灰色填充）
     */
    private fun preprocessImage(bitmap: Bitmap): Triple<OnnxTensor, Int, Int> {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        // Letterbox: 等比缩放
        val scale = minOf(
            INPUT_SIZE.toFloat() / originalWidth,
            INPUT_SIZE.toFloat() / originalHeight
        )
        val scaledWidth = (originalWidth * scale).toInt()
        val scaledHeight = (originalHeight * scale).toInt()

        // 保存参数供后处理坐标映射
        letterboxScale = scale
        letterboxPadX = (INPUT_SIZE - scaledWidth) / 2f
        letterboxPadY = (INPUT_SIZE - scaledHeight) / 2f
        Log.d(TAG, "preprocessImage: ${originalWidth}x${originalHeight}, scale=$letterboxScale, padX=$letterboxPadX, padY=$letterboxPadY")

        // 等比缩放（此步仍需分配临时 Bitmap，尺寸随输入变化）
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

        // 复用 640×640 padded bitmap：先涂灰底，再画上缩放后的图像
        reusableCanvas.drawColor(android.graphics.Color.rgb(114, 114, 114))
        val left = (INPUT_SIZE - scaledWidth) / 2f
        val top = (INPUT_SIZE - scaledHeight) / 2f
        reusableCanvas.drawBitmap(scaled, left, top, null)
        if (scaled != bitmap) scaled.recycle()

        // 复用像素数组和浮点数组，避免每帧 ~6.3MB 堆分配
        reusablePadded.getPixels(reusablePixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (i in reusablePixels.indices) {
            val pixel = reusablePixels[i]
            reusableFloats[i] = ((pixel shr 16) and 0xFF) / 255.0f                              // R
            reusableFloats[i + INPUT_SIZE * INPUT_SIZE] = ((pixel shr 8) and 0xFF) / 255.0f      // G
            reusableFloats[i + 2 * INPUT_SIZE * INPUT_SIZE] = (pixel and 0xFF) / 255.0f          // B
        }

        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val tensor = OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(reusableFloats),
            shape
        )

        return Triple(tensor, originalWidth, originalHeight)
    }

    /**
     * 执行 ONNX 推理并后处理结果
     * 自动根据模型输出形状计算类别数和掩码数
     */
    private fun runInference(
        session: OrtSession,
        inputTensor: OnnxTensor,
        withMask: Boolean,
        originalWidth: Int,
        originalHeight: Int,
        classNames: Array<String>? = null,  // 可选的类别名映射，null 则自动推断
        confThreshold: Float = CONF_THRESHOLD  // 可覆盖的置信度阈值
    ): List<DetectionResult> {
        val inputName = session.inputNames.first()
        val inputs = Collections.singletonMap(inputName, inputTensor)
        val outputs = session.run(inputs)

        // 从实际输出形状自动推断类别数和掩码数
        // YOLOv8-seg ONNX 可能有两种输出格式：
        //   [1, features(38), detections(8400)]  ← 需要转置
        //   [1, detections(8400), features(38)]  ← 直接使用
        val outputTensor = outputs[0]
        val rawOutput = outputTensor.value as Array<Array<FloatArray>>
        val dim0 = rawOutput[0].size        // 第一维大小
        val dim1 = rawOutput[0][0].size     // 第二维大小

        // 自动检测输出格式：特征维度通常 <=128（4 bbox + N classes + 32 masks）
        // 而检测数量通常是 8400（YOLOv8 的 anchor-free 检测头）
        val needsTranspose = dim0 < dim1  // 如果 dim0 小（如 38），说明是 [features, detections]

        val output: Array<FloatArray>
        val numFeatures: Int
        val numDetections: Int

        if (needsTranspose) {
            // 格式: [features, detections] → 需要转置为 [detections, features]
            numFeatures = dim0
            numDetections = dim1
            val raw2d = rawOutput[0]  // [features][detections]
            output = Array(numDetections) { detIdx ->
                FloatArray(numFeatures) { featIdx ->
                    raw2d[featIdx][detIdx]
                }
            }
            Log.d(TAG, "输出格式: [features=$numFeatures, detections=$numDetections] → 转置")
        } else {
            // 格式: [detections, features] → 直接使用
            numFeatures = dim1
            numDetections = dim0
            output = rawOutput[0]
            Log.d(TAG, "输出格式: [detections=$numDetections, features=$numFeatures] → 无需转置")
        }

        // 如果有第二个输出（proto masks），则有 32 个掩码系数
        val hasMaskOutput = outputs.size() > 1 && withMask
        val numMasks = if (hasMaskOutput) 32 else 0
        // 类别数 = 总特征数 - 4(bbox) - mask系数
        val numClasses = numFeatures - 4 - numMasks

        Log.d(TAG, "输出形状: features=$numFeatures, detections=$numDetections, masks=$numMasks, classes=$numClasses")

        // 提取 proto masks（仅一次，所有检测共享）
        // proto masks 是 ONNX 第二个输出，形状 [1, 32, maskH, maskW]
        val protoMasks: Array<Array<FloatArray>>? = if (hasMaskOutput && outputs.size() > 1) {
            try {
                val protoRaw = outputs[1].value as Array<*>
                val batch = protoRaw[0] as Array<*>
                val numProto = batch.size  // 通常为 32
                val firstChannel = batch[0] as Array<*>
                val mh = firstChannel.size
                val mw = (firstChannel[0] as FloatArray).size
                val result = Array(numProto) { i ->
                    Array(mh) { j ->
                        ((batch[i] as Array<*>)[j] as FloatArray).clone()
                    }
                }
                Log.d(TAG, "Proto masks: numProto=$numProto, maskH=$mh, maskW=$mw")
                result
            } catch (e: Exception) {
                Log.e(TAG, "提取 proto masks 失败，回退到 bbox 近似", e)
                null
            }
        } else {
            if (withMask) Log.w(TAG, "模型无 proto mask 输出 (outputs=${outputs.size()})，回退到 bbox 近似")
            null
        }

        val detections = mutableListOf<DetectionResult>()
        var maxConfSeen = 0f
        var topClassId = 0

        for (detIdx in 0 until numDetections) {
            val row = output[detIdx]

            // 前4个值是边界框 [cx, cy, w, h]
            val cx = row[0]
            val cy = row[1]
            val w = row[2]
            val h = row[3]

            // 找到最大类别置信度
            var maxConf = 0f
            var maxClassId = 0
            for (clsIdx in 0 until numClasses) {
                val conf = row[4 + clsIdx]
                if (conf > maxConf) {
                    maxConf = conf
                    maxClassId = clsIdx
                }
            }

            // 诊断日志：红绿灯模型时输出高得分检测的类别得分
            if (classNames === TRAFFIC_CLASSES && maxConf > 0.1f) {
                val scores = (0 until numClasses).map { clsIdx ->
                    classNames[clsIdx] to row[4 + clsIdx]
                }.sortedByDescending { it.second }.take(3)
                Log.d(TAG, "TRAFFIC_DEBUG det=$detIdx conf=${String.format("%.3f", maxConf)}: top3=${scores.joinToString { "${it.first}=${String.format("%.3f", it.second)}" }}")
            }

            // 过滤低置信度
            if (maxConf > maxConfSeen) { maxConfSeen = maxConf; topClassId = maxClassId }
            if (maxConf < confThreshold) continue

            // YOLO 输出 cx, cy, w, h 是归一化 [0,1] 坐标，先转换到 640×640 像素空间
            val gridX1 = (cx - w / 2f) * INPUT_SIZE
            val gridY1 = (cy - h / 2f) * INPUT_SIZE
            val gridX2 = (cx + w / 2f) * INPUT_SIZE
            val gridY2 = (cy + h / 2f) * INPUT_SIZE
            // 减去 letterbox padding（像素单位），再除以缩放比例得到原始图像像素坐标
            val rawX1 = (gridX1 - letterboxPadX) / letterboxScale
            val rawY1 = (gridY1 - letterboxPadY) / letterboxScale
            val rawX2 = (gridX2 - letterboxPadX) / letterboxScale
            val rawY2 = (gridY2 - letterboxPadY) / letterboxScale
            // 归一化到原始图像的 [0, 1] 范围
            val x1 = (rawX1 / originalWidth).coerceIn(0f, 1f)
            val y1 = (rawY1 / originalHeight).coerceIn(0f, 1f)
            val x2 = (rawX2 / originalWidth).coerceIn(0f, 1f)
            val y2 = (rawY2 / originalHeight).coerceIn(0f, 1f)

            // 诊断日志：输出完整的坐标映射链
            val isBlindPath = (classNames == null && numClasses <= 2 && maxClassId == 1) ||
                    (classNames != null && maxClassId < classNames.size && classNames[maxClassId] == "blind_path")
            if (isBlindPath) {
                Log.i(TAG, "═══ 盲道检测坐标映射 ═══")
                Log.i(TAG, "  输入图像: ${originalWidth}x${originalHeight}")
                Log.i(TAG, "  Letterbox: scale=$letterboxScale, padX=$letterboxPadX, padY=$letterboxPadY")
                Log.i(TAG, "  模型原始输出 (640×640 空间): cx=$cx, cy=$cy, w=$w, h=$h")
                Log.i(TAG, "  bbox 左上角 (640×640 像素): lx=$gridX1, ly=$gridY1")
                Log.i(TAG, "  bbox 左上角 (原图像素): px=$rawX1, py=$rawY1")
                Log.i(TAG, "  bbox 归一化: x1=$x1, y1=$y1, x2=$x2, y2=$y2")
                Log.i(TAG, "  bbox 中心 (归一化): cx=${(x1+x2)/2f}, cy=${(y1+y2)/2f}")
                Log.i(TAG, "  置信度: conf=$maxConf, class=$maxClassId")
                Log.i(TAG, "═══════════════════════")
            }

            // 解码分割掩码（使用 proto masks 进行真实掩码解码）
            var mask: SegmentationMask? = null
            if (numMasks > 0) {
                val maskCoeffs = FloatArray(32) { m -> row[4 + numClasses + m] }
                mask = decodeRealMask(maskCoeffs, protoMasks, x1, y1, x2, y2, originalWidth, originalHeight)
            }

            // 根据模型类型选择类别名称
            val className = when {
                // 使用提供的类别名映射（优先级最高）
                classNames != null && maxClassId < classNames.size ->
                    classNames[maxClassId]
                // 自定义2类分割模型：class 0=斑马线(crosswalk), class 1=盲道(blind_path)
                classNames == null && numClasses <= 2 -> when (maxClassId) {
                    0 -> "crosswalk"
                    1 -> "blind_path"
                    else -> "background"
                }
                // COCO 80类模型
                maxClassId < COCO_CLASSES.size -> COCO_CLASSES[maxClassId]
                else -> "class_$maxClassId"
            }

            detections.add(
                DetectionResult(
                    boundingBox = BoundingBox(x1, y1, x2, y2),
                    confidence = maxConf,
                    classId = maxClassId,
                    className = className,
                    mask = mask
                )
            )
        }

        val nmsResult = applyNMS(detections, IOU_THRESHOLD)
        Log.d(TAG, "runInference: raw=${detections.size}, afterNMS=${nmsResult.size}, maxConf=${maxConfSeen}, topClass=${topClassId}(${if (topClassId < classNames?.size ?: 0) classNames?.get(topClassId) else "class_${topClassId}"})")
        return nmsResult
    }

    /**
     * 解码 YOLOv8 分割掩码（使用 proto masks 真实解码）
     *
     * 流程：
     * 1. maskCoeffs[32] × protoMasks[32×H×W] → maskMatrix[H×W]（矩阵乘法 + sigmoid）
     * 2. 裁剪到 bbox 区域（letterbox 空间）
     * 3. 双线性插值缩放到 bbox 对应的原图尺寸
     * 4. 射线法提取轮廓多边形
     * 5. Letterbox 坐标反映射到原图归一化坐标
     *
     * @param maskCoeffs 32 个掩码系数
     * @param protoMasks proto 掩码数组 [numProto][maskH][maskW]，可为 null
     * @param x1 归一化 bbox 左
     * @param y1 归一化 bbox 上
     * @param x2 归一化 bbox 右
     * @param y2 归一化 bbox 下
     * @param originalWidth 原始图像宽度
     * @param originalHeight 原始图像高度
     * @return SegmentationMask 包含真实轮廓多边形
     */
    private fun decodeRealMask(
        maskCoeffs: FloatArray,
        protoMasks: Array<Array<FloatArray>>?,
        x1: Float, y1: Float, x2: Float, y2: Float,
        originalWidth: Int, originalHeight: Int
    ): SegmentationMask {
        // 如果没有 proto masks，回退到 bbox 近似
        if (protoMasks == null || protoMasks.isEmpty()) {
            return SegmentationMask(
                polygon = listOf(
                    Point(x1, y1), Point(x2, y1),
                    Point(x2, y2), Point(x1, y2)
                ),
                maskBitmap = null
            )
        }

        val maskH = protoMasks[0].size
        val maskW = protoMasks[0][0].size

        // 1. 计算 maskMatrix = maskCoeffs × protoMasks + sigmoid
        val maskMatrix = computeMaskMatrix(maskCoeffs, protoMasks, maskH, maskW)

        // 2. 将 bbox 映射到 mask 空间（考虑 letterbox 变换）
        val padX = letterboxPadX
        val padY = letterboxPadY
        val scale = letterboxScale

        // bbox 在 letterbox(640×640) 空间的像素坐标
        val boxLx1 = x1 * originalWidth * scale + padX
        val boxLy1 = y1 * originalHeight * scale + padY
        val boxLx2 = x2 * originalWidth * scale + padX
        val boxLy2 = y2 * originalHeight * scale + padY

        // 映射到 mask 空间
        val maskScaleX = maskW.toFloat() / INPUT_SIZE
        val maskScaleY = maskH.toFloat() / INPUT_SIZE
        val mX1 = (boxLx1 * maskScaleX).toInt().coerceIn(0, maskW - 1)
        val mY1 = (boxLy1 * maskScaleY).toInt().coerceIn(0, maskH - 1)
        val mX2 = (boxLx2 * maskScaleX).toInt().coerceIn(mX1 + 1, maskW)
        val mY2 = (boxLy2 * maskScaleY).toInt().coerceIn(mY1 + 1, maskH)

        val cropW = mX2 - mX1
        val cropH = mY2 - mY1
        if (cropW < 2 || cropH < 2) {
            return SegmentationMask(
                polygon = listOf(
                    Point(x1, y1), Point(x2, y1),
                    Point(x2, y2), Point(x1, y2)
                ),
                maskBitmap = null
            )
        }

        // 3. 裁剪 mask 到 bbox 区域
        val croppedMask = Array(cropH) { i ->
            FloatArray(cropW) { j ->
                maskMatrix[mY1 + i][mX1 + j]
            }
        }

        // 4. 缩放到 bbox 对应的原图尺寸
        val boxWidthPx = ((x2 - x1) * originalWidth).toInt().coerceAtLeast(4)
        val boxHeightPx = ((y2 - y1) * originalHeight).toInt().coerceAtLeast(4)
        val resizedMask = resizeMask(croppedMask, cropW, cropH, boxWidthPx, boxHeightPx)

        // 5. 创建下采样二值掩码网格（用于像素级渲染）
        // 40x40 = 1600 个格子，平衡精度与渲染性能
        val GRID_SIZE = 40
        val gridW = GRID_SIZE
        val gridH = GRID_SIZE
        val grid = BooleanArray(gridW * gridH)
        for (gy in 0 until gridH) {
            val srcY = gy.toFloat() / gridH * boxHeightPx
            for (gx in 0 until gridW) {
                val srcX = gx.toFloat() / gridW * boxWidthPx
                val ix = srcX.toInt().coerceIn(0, boxWidthPx - 1)
                val iy = srcY.toInt().coerceIn(0, boxHeightPx - 1)
                grid[gy * gridW + gx] = resizedMask[iy][ix] > 0.5f
            }
        }

        // 6. 提取轮廓多边形（用于后备渲染）
        val polygonPoints = extractMaskContour(resizedMask, boxWidthPx, boxHeightPx)

        // 7. 将轮廓点映射回原图归一化坐标
        val imagePolygon = polygonPoints.map { pt ->
            Point(
                x1 + pt.first / boxWidthPx * (x2 - x1),
                y1 + pt.second / boxHeightPx * (y2 - y1)
            )
        }

        Log.d(TAG, "decodeRealMask: bbox=[$x1,$y1,$x2,$y2], maskSpace=[$mX1,$mY1,$mX2,$mY2], " +
                "crop=${cropW}x${cropH}, box=${boxWidthPx}x${boxHeightPx}, " +
                "polygonPoints=${imagePolygon.size}, gridFilled=${grid.count { it }}/${grid.size}")

        return SegmentationMask(
            polygon = if (imagePolygon.size >= 3) imagePolygon else listOf(
                Point(x1, y1), Point(x2, y1), Point(x2, y2), Point(x1, y2)
            ),
            maskBitmap = null,
            maskGrid = grid,
            gridWidth = gridW,
            gridHeight = gridH
        )
    }

    /**
     * 计算 maskMatrix = sigmoid(maskCoeffs × protoMasks)
     * maskCoeffs: [32], protoMasks: [32][maskH][maskW]
     * 返回: FloatArray[maskH][maskW] 二值化结果
     */
    private fun computeMaskMatrix(
        maskCoeffs: FloatArray,
        protoMasks: Array<Array<FloatArray>>,
        maskH: Int,
        maskW: Int
    ): Array<FloatArray> {
        val result = Array(maskH) { FloatArray(maskW) }
        val numProto = minOf(maskCoeffs.size, protoMasks.size)

        for (k in 0 until numProto) {
            val c = maskCoeffs[k]
            if (c == 0f) continue
            val protoK = protoMasks[k]
            for (i in 0 until maskH) {
                val ri = result[i]
                val pi = protoK[i]
                for (j in 0 until maskW) {
                    ri[j] += c * pi[j]
                }
            }
        }

        // sigmoid + 二值化
        for (i in 0 until maskH) {
            val ri = result[i]
            for (j in 0 until maskW) {
                ri[j] = if (1f / (1f + kotlin.math.exp(-ri[j])) > MASK_THRESHOLD) 1f else 0f
            }
        }

        return result
    }

    /**
     * 双线性插值缩放掩码
     */
    private fun resizeMask(
        src: Array<FloatArray>, srcW: Int, srcH: Int,
        dstW: Int, dstH: Int
    ): Array<FloatArray> {
        val result = Array(dstH) { FloatArray(dstW) }
        for (y in 0 until dstH) {
            val srcY = y * (srcH - 1).toFloat() / (dstH - 1).coerceAtLeast(1)
            val y0 = srcY.toInt().coerceIn(0, srcH - 1)
            val y1 = (y0 + 1).coerceAtMost(srcH - 1)
            val fy = srcY - y0

            for (x in 0 until dstW) {
                val srcX = x * (srcW - 1).toFloat() / (dstW - 1).coerceAtLeast(1)
                val x0 = srcX.toInt().coerceIn(0, srcW - 1)
                val x1 = (x0 + 1).coerceAtMost(srcW - 1)
                val fx = srcX - x0

                val v00 = src[y0][x0]
                val v01 = src[y0][x1]
                val v10 = src[y1][x0]
                val v11 = src[y1][x1]

                result[y][x] = v00 * (1 - fx) * (1 - fy) + v01 * fx * (1 - fy) +
                        v10 * (1 - fx) * fy + v11 * fx * fy
            }
        }
        return result
    }

    /**
     * 从二值掩码提取轮廓多边形（射线法）
     * 从掩码中心向 24 个方向发射射线，找到每个方向上的边界点
     *
     * @param mask 二值掩码 [height][width]
     * @param width 掩码宽度
     * @param height 掩码高度
     * @return 轮廓点列表 [(x, y), ...]
     */
    private fun extractMaskContour(
        mask: Array<FloatArray>,
        width: Int,
        height: Int
    ): List<Pair<Float, Float>> {
        val numRays = 24
        val cx = width / 2f
        val cy = height / 2f
        val points = mutableListOf<Pair<Float, Float>>()

        for (i in 0 until numRays) {
            val angle = 2.0 * Math.PI * i / numRays
            val dx = kotlin.math.cos(angle).toFloat()
            val dy = kotlin.math.sin(angle).toFloat()

            var lastX = cx
            var lastY = cy

            var step = 1f
            while (step < maxOf(width, height).toFloat()) {
                val px = cx + dx * step
                val py = cy + dy * step
                val ix = px.toInt()
                val iy = py.toInt()

                if (ix < 0 || ix >= width || iy < 0 || iy >= height) {
                    break
                }

                if (mask[iy][ix] < 0.5f) {
                    // 找到边界：取最后一个内部点
                    points.add(lastX to lastY)
                    break
                }
                lastX = px
                lastY = py
                step += 1f
            }
        }

        if (points.size < 3) {
            // 回退：扫描线法查找上下左右极值点
            val foundPoints = mutableListOf<Pair<Float, Float>>()
            for (y in 0 until height step maxOf(1, height / 12)) {
                for (x in 0 until width) {
                    if (mask[y][x] > 0.5f) { foundPoints.add(x.toFloat() to y.toFloat()); break }
                }
                for (x in width - 1 downTo 0) {
                    if (mask[y][x] > 0.5f) { foundPoints.add(x.toFloat() to y.toFloat()); break }
                }
            }
            for (x in 0 until width step maxOf(1, width / 12)) {
                for (y in 0 until height) {
                    if (mask[y][x] > 0.5f) { foundPoints.add(x.toFloat() to y.toFloat()); break }
                }
                for (y in height - 1 downTo 0) {
                    if (mask[y][x] > 0.5f) { foundPoints.add(x.toFloat() to y.toFloat()); break }
                }
            }
            if (foundPoints.size >= 3) return foundPoints
            // 最终回退：bbox 四角
            return listOf(0f to 0f, width.toFloat() to 0f,
                width.toFloat() to height.toFloat(), 0f to height.toFloat())
        }

        return points
    }

    /**
     * 采样掩码值（双线性插值）
     */
    private fun sampleMask(mask: Array<FloatArray>, x: Float, y: Float): Float {
        val x0 = x.toInt().coerceIn(0, mask[0].size - 1)
        val y0 = y.toInt().coerceIn(0, mask.size - 1)
        val x1 = (x0 + 1).coerceAtMost(mask[0].size - 1)
        val y1 = (y0 + 1).coerceAtMost(mask.size - 1)
        val fx = x - x0
        val fy = y - y0
        return mask[y0][x0] * (1 - fx) * (1 - fy) +
                mask[y0][x1] * fx * (1 - fy) +
                mask[y1][x0] * (1 - fx) * fy +
                mask[y1][x1] * fx * fy
    }

    /**
     * 安全采样掩码值（整数坐标）
     */
    private fun sampleMaskSafe(mask: Array<FloatArray>, x: Int, y: Int): Float {
        val cx = x.coerceIn(0, mask[0].size - 1)
        val cy = y.coerceIn(0, mask.size - 1)
        return mask[cy][cx]
    }

    /**
     * 非极大值抑制 (NMS)
     * 去除重叠度过高的低置信度检测框
     */
    private fun applyNMS(
        detections: List<DetectionResult>,
        iouThreshold: Float
    ): List<DetectionResult> {
        if (detections.isEmpty()) return emptyList()

        // 按置信度降序排序
        val sorted = detections.sortedByDescending { it.confidence }
        val selected = mutableListOf<DetectionResult>()
        val suppressed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (suppressed[i]) continue

            selected.add(sorted[i])

            // 抑制与当前选中框 IoU 过高的后续框
            for (j in (i + 1) until sorted.size) {
                if (suppressed[j]) continue
                if (sorted[i].classId == sorted[j].classId) {
                    val iou = calculateIoU(
                        sorted[i].boundingBox,
                        sorted[j].boundingBox
                    )
                    if (iou > iouThreshold) {
                        suppressed[j] = true
                    }
                }
            }
        }

        return selected
    }

    /**
     * 计算两个边界框的 IoU (Intersection over Union)
     */
    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val interX1 = maxOf(box1.x1, box2.x1)
        val interY1 = maxOf(box1.y1, box2.y1)
        val interX2 = minOf(box1.x2, box2.x2)
        val interY2 = minOf(box1.y2, box2.y2)

        val interArea = maxOf(0f, interX2 - interX1) * maxOf(0f, interY2 - interY1)
        val unionArea = box1.area + box2.area - interArea

        return if (unionArea > 0) interArea / unionArea else 0f
    }

    /**
     * 释放模型资源
     */
    fun release() {
        segSession?.close()
        segSession = null
        detectSession?.close()
        detectSession = null
        trafficSession?.close()
        trafficSession = null
        shoppingSession?.close()
        shoppingSession = null
        claSession?.close()
        claSession = null
        lytSession?.close()
        lytSession = null
        segModelLoaded = false
        detectModelLoaded = false
        trafficModelLoaded = false
        shoppingModelLoaded = false
        claModelLoaded = false
        lytModelLoaded = false
        if (!reusablePadded.isRecycled) reusablePadded.recycle()
        Log.i(TAG, "YOLO 推理引擎资源已释放")
    }
}
