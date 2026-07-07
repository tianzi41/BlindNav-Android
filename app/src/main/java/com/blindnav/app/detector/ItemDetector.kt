/**
 * ItemDetector.kt - 物品查找检测器
 * 使用 YOLO 检测目标物品，结合手部检测引导用户抓取
 */
package com.blindnav.app.detector

import android.graphics.Bitmap
import android.util.Log
import com.blindnav.app.data.BoundingBox
import com.blindnav.app.data.DetectionResult
import com.blindnav.app.ml.YoloOnnxEngine

/**
 * 物品查找检测器
 * 检测用户指定的目标物品，计算相对位置并生成引导指令
 */
class ItemDetector(
    private val engine: YoloOnnxEngine
) {
    companion object {
        private const val TAG = "ItemDetector"
        private const val MIN_CONFIDENCE = 0.3f

        // 位置分区
        private const val LEFT_THRESHOLD = 0.35f
        private const val RIGHT_THRESHOLD = 0.65f
        private const val TOP_THRESHOLD = 0.35f
        private const val BOTTOM_THRESHOLD = 0.65f

        // 接近判定阈值（物品面积占比）
        private const val NEAR_THRESHOLD = 0.1f

                // 物品名称映射：中文输入 -> OIV7 英文类名
        // 搜索时先用 shopping 模型（OIV7 601类），再用 COCO 模型（80类）
        val ITEM_NAME_MAP = mapOf(
            "CD播放器" to "CD player",
            "三明治" to "Sandwich",
            "三脚架" to "Tripod",
            "个人护理" to "Personal care",
            "乌龟" to "Turtle",
            "乐器" to "Instrument",
            "乐器" to "Musical instrument",
            "书" to "Book",
            "书架" to "Bookcase",
            "五斗柜" to "Chest of drawers",
            "人" to "Person",
            "人体" to "Human body",
            "企鹅" to "Penguin",
            "便利店" to "Convenience store",
            "信封" to "Envelope",
            "停车标志" to "Stop sign",
            "停车计时器" to "Parking meter",
            "兔子" to "Rabbit",
            "公交车" to "Bus",
            "公牛" to "Bull",
            "冰淇淋" to "Ice cream",
            "冰箱" to "Refrigerator",
            "冲浪板" to "Surfboard",
            "凉鞋" to "Sandal",
            "凳子" to "Stool",
            "出租车" to "Taxi",
            "刀" to "Knife",
            "创可贴" to "Band-aid",
            "刷子" to "Brush",
            "剑" to "Sword",
            "剪刀" to "Scissors",
            "办公用品" to "Office supplies",
            "动物" to "Animal",
            "勺子" to "Ladle",
            "勺子" to "Spoon",
            "包装材料" to "Packing material",
            "匕首" to "Dagger",
            "化妆品" to "Cosmetic",
            "医疗器械" to "Medical equipment",
            "半身像" to "Bust",
            "华夫饼" to "Waffle",
            "单板滑雪" to "Snowboard",
            "南瓜" to "Pumpkin",
            "南瓜" to "Squash",
            "卡带机" to "Cassette deck",
            "卡车" to "Truck",
            "卫生纸" to "Toilet paper",
            "卷心菜" to "Cabbage",
            "卷饼" to "Burrito",
            "叉子" to "Fork",
            "口琴" to "Harmonica",
            "台球" to "Billiard ball",
            "台球桌" to "Billiard table",
            "吉他" to "Guitar",
            "吊扇" to "Ceiling fan",
            "吊灯" to "Chandelier",
            "向日葵" to "Sunflower",
            "听诊器" to "Stethoscope",
            "吸管" to "Drinking straw",
            "吹风机" to "Hair dryer",
            "咖啡" to "Coffee",
            "咖啡杯" to "Coffee cup",
            "哈密瓜" to "Cantaloupe",
            "哑铃" to "Dumbbell",
            "喷泉" to "Fountain",
            "嘴" to "Human mouth",
            "围巾" to "Scarf",
            "土豆" to "Potato",
            "圣诞树" to "Christmas tree",
            "地毯" to "Rug",
            "地铁" to "Subway",
            "坦克" to "Tank",
            "城堡" to "Castle",
            "塑料袋" to "Plastic bag",
            "塔" to "Tower",
            "墨西哥帽" to "Sombrero",
            "壁炉" to "Fireplace",
            "大提琴" to "Cello",
            "大炮" to "Cannon",
            "大盘子" to "Platter",
            "大衣" to "Coat",
            "大象" to "Elephant",
            "天鹅" to "Swan",
            "太阳镜" to "Sunglasses",
            "头" to "Human head",
            "头发" to "Human hair",
            "头戴耳机" to "Headphones",
            "头盔" to "Helmet",
            "头骨" to "Skull",
            "夹克" to "Jacket",
            "女孩" to "Girl",
            "女性" to "Woman",
            "女衬衫" to "Blouse",
            "奶油" to "Cream",
            "奶酪" to "Cheese",
            "娃娃" to "Doll",
            "婴儿床" to "Infant bed",
            "安全带" to "Seat belt",
            "室内植物" to "Houseplant",
            "家具" to "Furniture",
            "导弹" to "Missile",
            "寿司" to "Sushi",
            "小号" to "Trumpet",
            "小提琴" to "Violin",
            "尺子" to "Ruler",
            "尿布" to "Diaper",
            "山羊" to "Goat",
            "工具" to "Tool",
            "帐篷" to "Tent",
            "帽子" to "Hat",
            "平板电脑" to "Tablet",
            "平衡木" to "Balance beam",
            "平衡车" to "Segway",
            "广告牌" to "Billboard",
            "床" to "Bed",
            "床头柜" to "Nightstand",
            "建筑" to "Building",
            "开关" to "Light switch",
            "开瓶器" to "Bottle opener",
            "开罐器" to "Can opener",
            "弓箭" to "Bow and arrow",
            "彩虹" to "Rainbow",
            "微波炉" to "Microwave oven",
            "恐龙" to "Dinosaur",
            "意面" to "Pasta",
            "戒指" to "Ring",
            "房子" to "House",
            "手" to "Human hand",
            "手套" to "Glove",
            "手推车" to "Wagon",
            "手提包" to "Handbag",
            "手提箱" to "Suitcase",
            "手机" to "Cell phone",
            "手枪" to "Handgun",
            "手电筒" to "Flashlight",
            "手电筒" to "Torch",
            "手臂" to "Human arm",
            "手表" to "Watch",
            "手镯" to "Bracelet",
            "手风琴" to "Accordion",
            "打印机" to "Printer",
            "打蛋器" to "Whisk",
            "托盘" to "Serving tray",
            "扳手" to "Wrench",
            "护目镜" to "Goggles",
            "披萨" to "Pizza",
            "抽屉" to "Drawer",
            "担架" to "Stretcher",
            "拐杖" to "Crutch",
            "挂钟" to "Wall clock",
            "排球" to "Volleyball",
            "推车" to "Cart",
            "搅拌机" to "Blender",
            "搅拌机" to "Mixer",
            "摩托车" to "Motorcycle",
            "救护车" to "Ambulance",
            "文件柜" to "Filing cabinet",
            "文具" to "Stationery",
            "斑马" to "Zebra",
            "斧头" to "Axe",
            "旗帜" to "Flag",
            "时钟" to "Clock",
            "昆虫" to "Insect",
            "易拉罐" to "Tin can",
            "显示器" to "Computer monitor",
            "曲奇" to "Cookie",
            "曲棍球棒" to "Hockey stick",
            "杂志" to "Magazine",
            "杯子" to "Cup",
            "松饼" to "Muffin",
            "松鼠" to "Squirrel",
            "枕头" to "Pillow",
            "果汁" to "Juice",
            "枫树" to "Maple",
            "架子" to "Shelf",
            "柚子" to "Grapefruit",
            "柠檬" to "Lemon",
            "桃子" to "Peach",
            "桌子" to "Desk",
            "桌子" to "Table",
            "桥" to "Bridge",
            "桨" to "Paddle",
            "桶" to "Barrel",
            "梨" to "Pear",
            "梯子" to "Ladder",
            "梳子" to "Comb",
            "棒球手套" to "Baseball glove",
            "棒球棒" to "Baseball bat",
            "棕榈树" to "Palm",
            "棺材" to "Coffin",
            "椅子" to "Chair",
            "植物" to "Plant",
            "椰子" to "Coconut",
            "樱桃" to "Cherry",
            "橙子" to "Orange",
            "橡皮" to "Eraser",
            "橱柜" to "Cabinetry",
            "步枪" to "Rifle",
            "武器" to "Weapon",
            "毛巾" to "Towel",
            "气球" to "Balloon",
            "水上摩托" to "Jet ski",
            "水壶" to "Jug",
            "水壶" to "Kettle",
            "水果" to "Fruit",
            "水桶" to "Bucket",
            "水槽" to "Sink",
            "水母" to "Jellyfish",
            "水龙头" to "Faucet",
            "水龙头" to "Tap",
            "汉堡包" to "Hamburger",
            "汽车" to "Car",
            "汽车零件" to "Auto part",
            "沙发" to "Couch",
            "沙发床" to "Sofa bed",
            "沙拉" to "Salad",
            "沙袋" to "Punching bag",
            "河马" to "Hippopotamus",
            "注射器" to "Syringe",
            "泰迪熊" to "Teddy bear",
            "泳帽" to "Swim cap",
            "泳衣" to "Swimwear",
            "洋蓟" to "Artichoke",
            "洗碗机" to "Dishwasher",
            "洗衣机" to "Washing machine",
            "浣熊" to "Raccoon",
            "浴室柜" to "Bathroom cabinet",
            "浴室用品" to "Bathroom accessory",
            "浴缸" to "Bathtub",
            "海报" to "Poster",
            "海星" to "Starfish",
            "海洋生物" to "Marine invertebrates",
            "海滩" to "Beach",
            "海狮" to "Sea lion",
            "海豚" to "Dolphin",
            "海豹" to "Harbor seal",
            "海马" to "Seahorse",
            "海龟" to "Sea turtle",
            "消防栓" to "Fire hydrant",
            "消防车" to "Firetruck",
            "游泳池" to "Swimming pool",
            "溜冰鞋" to "Roller skates",
            "滑板" to "Skateboard",
            "滑雪板" to "Ski",
            "潜艇" to "Submarine",
            "火箭" to "Rocket",
            "火车" to "Train",
            "火鸡" to "Turkey",
            "灯" to "Lamp",
            "灯塔" to "Lighthouse",
            "灯泡" to "Light bulb",
            "灯笼" to "Lantern",
            "炒锅" to "Wok",
            "炸弹" to "Bomb",
            "烘焙食品" to "Baked goods",
            "烛台" to "Candle holder",
            "烤箱" to "Oven",
            "烤面包机" to "Toaster",
            "热狗" to "Hot dog",
            "煎锅" to "Frying pan",
            "煎饼" to "Pancake",
            "熊猫" to "Panda",
            "燃气灶" to "Gas stove",
            "爬行动物" to "Reptile",
            "牙刷" to "Toothbrush",
            "牛" to "Cow",
            "牛仔裤" to "Jeans",
            "牡蛎" to "Oyster",
            "犀牛" to "Rhinoceros",
            "犰狳" to "Armadillo",
            "狐狸" to "Fox",
            "狗" to "Dog",
            "独木舟" to "Canoe",
            "独轮车" to "Unicycle",
            "狮子" to "Lion",
            "猎豹" to "Cheetah",
            "猪" to "Pig",
            "猫" to "Cat",
            "猫头鹰" to "Owl",
            "猴子" to "Monkey",
            "玩具" to "Toy",
            "玫瑰" to "Rose",
            "玻璃杯" to "Glass",
            "班卓琴" to "Banjo",
            "球" to "Ball",
            "球拍" to "Racket",
            "瓶子" to "Bottle",
            "甜点" to "Dessert",
            "甜甜圈" to "Doughnut",
            "电源线" to "Power cord",
            "电视" to "Television",
            "电话" to "Telephone",
            "电钻" to "Drill",
            "电风扇" to "Mechanical fan",
            "男孩" to "Boy",
            "番茄" to "Tomato",
            "白板" to "Whiteboard",
            "百合花" to "Lily",
            "百吉饼" to "Bagel",
            "皇冠" to "Crown",
            "盒子" to "Box",
            "盘子" to "Plate",
            "直升机" to "Helicopter",
            "相机" to "Camera",
            "相框" to "Picture frame",
            "眼睛" to "Human eye",
            "短裤" to "Shorts",
            "研磨机" to "Grinder",
            "砧板" to "Cutting board",
            "硬币" to "Coin",
            "碗" to "Bowl",
            "碟子" to "Saucer",
            "窗帘" to "Curtain",
            "窗户" to "Window",
            "竖琴" to "Harp",
            "笔" to "Pen",
            "笔记本电脑" to "Laptop",
            "筷子" to "Chopsticks",
            "粉底" to "Face powder",
            "糕点" to "Pastry",
            "糖果" to "Candy",
            "红绿灯" to "Traffic light",
            "纸" to "Paper",
            "纸巾" to "Paper towel",
            "纸巾盒" to "Facial tissue holder",
            "纸杯蛋糕" to "Cupcake",
            "线缆" to "Cord",
            "缝纫机" to "Sewing machine",
            "罐头" to "Can",
            "网球" to "Tennis ball",
            "网球拍" to "Tennis racket",
            "羊角面包" to "Croissant",
            "羊驼" to "Alpaca",
            "美洲豹" to "Jaguar",
            "羚羊" to "Antelope",
            "老虎" to "Tiger",
            "老鼠" to "Mouse",
            "耳朵" to "Human ear",
            "耳机" to "Earphone",
            "耳环" to "Earring",
            "背包" to "Backpack",
            "胡萝卜" to "Carrot",
            "胡须" to "Human beard",
            "胶带" to "Adhesive tape",
            "脚" to "Human foot",
            "脸" to "Human face",
            "腿" to "Human leg",
            "自动扶梯" to "Escalator",
            "自行车" to "Bicycle",
            "自行车头盔" to "Bicycle helmet",
            "自行车轮" to "Bicycle wheel",
            "臭鼬" to "Skunk",
            "船" to "Boat",
            "船只" to "Watercraft",
            "芒果" to "Mango",
            "花" to "Flower",
            "花园" to "Garden",
            "花瓶" to "Vase",
            "苹果" to "Apple",
            "茄子" to "Eggplant",
            "茶" to "Tea",
            "茶几" to "Coffee table",
            "茶壶" to "Teapot",
            "草莓" to "Strawberry",
            "菜刀" to "Kitchen knife",
            "菠萝" to "Pineapple",
            "萝卜" to "Radish",
            "萨克斯" to "Saxophone",
            "葡萄" to "Grape",
            "葡萄酒" to "Wine",
            "蔬菜" to "Vegetable",
            "薯条" to "French fries",
            "薰衣草" to "Lavender",
            "虾" to "Shrimp",
            "蚂蚁" to "Ant",
            "蚊子" to "Mosquito",
            "蛇" to "Snake",
            "蛋挞" to "Tart",
            "蛋糕" to "Cake",
            "蛋糕架" to "Cake stand",
            "蜂蜜" to "Honey",
            "蜘蛛" to "Spider",
            "蜜蜂" to "Bee",
            "蜡烛" to "Candle",
            "蜥蜴" to "Lizard",
            "蜻蜓" to "Dragonfly",
            "蝙蝠" to "Bat (Animal)",
            "蝴蝶" to "Butterfly",
            "螃蟹" to "Crab",
            "螺丝刀" to "Screwdriver",
            "行李箱" to "Luggage & bags",
            "衣柜" to "Wardrobe",
            "衬衫" to "Shirt",
            "袋鼠" to "Kangaroo",
            "袜子" to "Sock",
            "裤子" to "Trousers",
            "西兰花" to "Broccoli",
            "西瓜" to "Watermelon",
            "西葫芦" to "Zucchini",
            "西装" to "Suit",
            "计算器" to "Calculator",
            "调料瓶" to "Salt and pepper shakers",
            "贝壳" to "Shell",
            "路灯" to "Street light",
            "车辆" to "Vehicle",
            "轮子" to "Wheel",
            "轮椅" to "Wheelchair",
            "轮胎" to "Tire",
            "软呢帽" to "Fedora",
            "运动器材" to "Sports equipment",
            "连衣裙" to "Dress",
            "迷你裙" to "Miniskirt",
            "遥控器" to "Remote control",
            "遮阳帽" to "Sun hat",
            "酒杯" to "Wine glass",
            "野餐篮" to "Picnic basket",
            "量杯" to "Measuring cup",
            "金鱼" to "Goldfish",
            "钢琴" to "Piano",
            "钥匙" to "Key",
            "铅笔" to "Pencil",
            "铜雕" to "Bronze sculpture",
            "锅" to "Pot",
            "锤子" to "Hammer",
            "键盘" to "Computer keyboard",
            "镜子" to "Mirror",
            "长号" to "Trombone",
            "长椅" to "Bench",
            "长笛" to "Flute",
            "长颈鹿" to "Giraffe",
            "门" to "Door",
            "门廊" to "Porch",
            "门把手" to "Door handle",
            "闹钟" to "Alarm clock",
            "降落伞" to "Parachute",
            "雕塑" to "Sculpture",
            "雨伞" to "Umbrella",
            "雪人" to "Snowman",
            "青蛙" to "Frog",
            "靠垫" to "Cushion",
            "面包" to "Bread",
            "面包车" to "Van",
            "靴子" to "Boot",
            "鞋子" to "Footwear",
            "音乐播放器" to "Ipod",
            "项链" to "Necklace",
            "领带" to "Tie",
            "风扇" to "Fan",
            "风琴" to "Organ",
            "风筝" to "Kite",
            "飞机" to "Aircraft",
            "飞机" to "Airplane",
            "飞盘" to "Flying disc",
            "飞镖靶" to "Dartboard",
            "食品加工机" to "Food processor",
            "食物" to "Food",
            "食用油" to "Cooking spray",
            "餐具" to "Tableware",
            "餐桌" to "Kitchen & dining room table",
            "饮料" to "Drink",
            "饺子" to "Dumpling",
            "香水" to "Perfume",
            "香蕉" to "Banana",
            "马" to "Horse",
            "马克杯" to "Mug",
            "马桶" to "Toilet",
            "驳船" to "Barge",
            "骆驼" to "Camel",
            "骰子" to "Dice",
            "高尔夫球" to "Golf ball",
            "高尔夫车" to "Golf cart",
            "高跟鞋" to "High heels",
            "鱼" to "Fish",
            "鱿鱼" to "Squid",
            "鲨鱼" to "Shark",
            "鲸鱼" to "Whale",
            "鳄梨酱" to "Guacamole",
            "鸟" to "Bird",
            "鸡尾酒" to "Cocktail",
            "鸡肉" to "Chicken",
            "鸡蛋" to "Egg",
            "鸭子" to "Duck",
            "鸵鸟" to "Ostrich",
            "鹦鹉" to "Parrot",
            "鹰" to "Eagle",
            "鹿" to "Deer",
            "麦克风" to "Microphone",
            "麻雀" to "Sparrow",
            "黄瓜" to "Cucumber",
            "鼓" to "Drum",
            "鼠标" to "Computer mouse",
            "鼻子" to "Human nose",
            "龙虾" to "Lobster",
        )
    }

    /**
     * 物品查找结果
     */
    data class ItemSearchResult(
        val found: Boolean,
        val detection: DetectionResult? = null,
        val horizontalGuidance: String = "",
        val verticalGuidance: String = "",
        val distanceGuidance: String = "",
        val isNear: Boolean = false
    )

    /**
     * 查找指定物品
     * 优先使用商品专用模型，回退到 COCO 通用模型
     * @param bitmap 输入图像
     * @param targetName 目标物品名称（英文或中文）
     * @return 物品查找结果
     */
    fun search(bitmap: Bitmap, targetName: String): ItemSearchResult {
        // 物品名称映射（中文->英文）
        val englishName = ITEM_NAME_MAP[targetName] ?: targetName.lowercase()

        var detections: List<DetectionResult> = emptyList()

        // 1. 优先使用 OIV7 模型（601 类，覆盖日常大部分物品）
        if (engine.isShoppingModelLoaded()) {
            detections = engine.runShoppingDetection(bitmap)
        }

        // 2. OIV7 模型没找到 → 用 COCO 检测模型（80 类，通用物体）
        if (detections.isEmpty() && engine.isDetectModelLoaded()) {
            detections = engine.runDetection(bitmap)
        }

        // 过滤出目标物品检测结果（双向不区分大小写匹配）
        val itemDetections = detections.filter { detection ->
            val cn = detection.className.lowercase()
            val en = englishName.lowercase()
            (cn.contains(en) || en.contains(cn)) &&
            detection.confidence >= MIN_CONFIDENCE
        }

        if (itemDetections.isEmpty()) {
            return ItemSearchResult(found = false)
        }

        // 选择置信度最高的物品
        val bestDetection = itemDetections.maxByOrNull { it.confidence }
            ?: return ItemSearchResult(found = false)

        val box = bestDetection.boundingBox

        // 计算水平引导
        val horizontalGuidance = getHorizontalGuidance(box.centerX)

        // 计算垂直引导
        val verticalGuidance = getVerticalGuidance(box.centerY)

        // 计算距离引导
        val areaRatio = box.area
        val isNear = areaRatio >= NEAR_THRESHOLD
        val distanceGuidance = getDistanceGuidance(areaRatio)

        return ItemSearchResult(
            found = true,
            detection = bestDetection,
            horizontalGuidance = horizontalGuidance,
            verticalGuidance = verticalGuidance,
            distanceGuidance = distanceGuidance,
            isNear = isNear
        )
    }

    /**
     * 获取水平方向引导
     */
    private fun getHorizontalGuidance(centerX: Float): String {
        return when {
            centerX < LEFT_THRESHOLD -> "在画面左侧"
            centerX > RIGHT_THRESHOLD -> "在画面右侧"
            else -> "在画面中间"
        }
    }

    /**
     * 获取垂直方向引导
     */
    private fun getVerticalGuidance(centerY: Float): String {
        return when {
            centerY < TOP_THRESHOLD -> "向上"
            centerY > BOTTOM_THRESHOLD -> "向下"
            else -> ""
        }
    }

    /**
     * 获取距离引导
     */
    private fun getDistanceGuidance(areaRatio: Float): String {
        return when {
            areaRatio >= NEAR_THRESHOLD -> "已到达目标前方，请注意。"
            areaRatio >= 0.03f -> "目标就在前方，请慢慢靠近。"
            else -> "远处有目标，继续前行。"
        }
    }

}
