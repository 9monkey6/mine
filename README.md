## 版本迭代 

### V1.0 【当前版本】& 容器化部署踩坑记录
实现功能：
1. Gateway 网关 SpringBoot 项目本地正常运行、Nacos 注册发现正常
2. 完成 Gateway Docker 容器化打包、镜像构建、容器部署
3. 解决端口占用、容器名冲突、不可执行 jar、Nacos 跨网络注册等问题
项目背景
将 SpringCloud Gateway 网关打包为 Docker 容器化部署，整合 Nacos 注册中心 & 配置中心，实现微服务容器化运行；本地 IDEA 直接启动服务可正常注册 Nacos，Docker 部署后出现各类连通、启动、注册异常，逐步排查解决。
问题 1：docker build 构建镜像命令报错
问题描述
执行 docker build -t gateway:1.0 构建镜像，提示：docker buildx build requires exactly 1 argument
原因
缺少上下文路径，docker build 命令末尾必须指定当前目录上下文
解决方式
使用完整构建命令，指定当前目录：
bash
运行
docker build -t gateway:1.0 .
问题 2：容器启动报错：80 端口被占用
问题描述
启动容器提示：Bind for 0.0.0.0:80 failed: port is already allocated
原因
Gateway 默认内置端口为 80，本机 80 端口被其他程序占用
解决方式
在配置文件手动指定服务端口为 8888，避免端口冲突
Docker 启动使用端口映射，隔离容器端口与宿主机端口
yaml
server:
port: 8888
问题 3：容器名称冲突，无法重复创建容器
问题描述
重复启动容器报错：Conflict. The container name "/gateway" is already in use
原因
旧容器未删除，gateway 容器名称全局唯一，不可重复
解决方式
部署前强制清理旧容器，再重新创建：
bash
运行
docker rm -f gateway
问题 4：Docker 容器启动成功，但服务未运行
问题描述
容器状态正常，但查看日志：no main manifest attribute, in /app.jar
原因
Maven 打包缺少 spring-boot-maven-plugin 插件，打包出的 Jar 为非可执行 Jar，无启动入口
解决方式
在微服务启动模块（Gateway、业务服务） 的 pom.xml 中补充 SpringBoot 打包插件；
公共模块 / 父工程不添加，避免打包异常。
xml
<build>
<plugins>
<plugin>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-maven-plugin</artifactId>
</plugin>
</plugins>
</build>
补充插件后，重新执行 mvn clean package 打包，重建镜像。
问题 5：Docker 服务无法注册到 Nacos（核心难点）
问题描述
本地 IDEA 启动：localhost:8848 连接 Nacos，注册正常；
Docker 容器内使用 localhost:8848：无法连接宿主机 Nacos，服务无法注册；
Windows Docker 对 --net=host 主机网络模式兼容存在缺陷，该方案不稳定。
根本原因
Docker 容器是独立网络环境，容器内 localhost 指向容器自身，并非宿主机；
Windows 平台 Docker 网络驱动限制，无法完美共享宿主机网络。
阶段性方案 & 最终规范方案
临时方案：配置文件硬编码宿主机局域网 IP（192.168.0.113:8848），可临时解决注册问题，但换网络环境需修改代码，不规范、不适合面试 / 生产。
企业级通用方案（最终采用，面试加分）
配置文件使用环境变量占位符，不硬编码任何 IP，代码通用；
本地开发默认使用 localhost，Docker 部署通过启动命令动态注入宿主机 IP；
一套配置适配：本地开发、Docker 容器、服务器生产环境。
yaml
# 最终通用 bootstrap.yml 配置
spring:
application:
name: gateway
cloud:
nacos:
discovery:
server-addr: ${NACOS_HOST:localhost}:8848
config:
server-addr: ${NACOS_HOST:localhost}:8848
file-extension: yaml
server:
port: 8888
bash
运行
# Docker 启动命令：通过 -e 动态传入宿主机IP，配置文件无需修改
docker run -d -p 8888:8888 --name gateway -e NACOS_HOST=192.168.0.113 gateway:1.0
最终总结
容器化部署必须区分容器网络与宿主机网络，禁止直接在容器内使用 localhost 访问本地中间件；
可执行 SpringBoot 项目打包必须依赖专属打包插件，否则容器无法启动；
规避硬编码 IP，使用环境变量 + 配置占位符实现多环境适配，符合企业开发规范；
掌握 Docker 常用运维命令：容器删除、日志排查、端口映射、环境变量注入，快速定位部署异常。


V2.0 迭代更新：Docker-Compose 编排 + 微服务跨容器通信 + 依赖服务优雅启动优化
基于 V1.0 基础上，完成全中间件 + 微服务一体化容器编排，解决集群化部署、容器网络互通、服务启动时序、Nacos 连接拒绝等深度容器化难题，完善生产级部署规范。
新增解决核心问题
问题 6：Docker-Compose 多服务网络隔离，容器间无法互相通信
问题描述
单独部署 Gateway、Nacos、MySQL、Redis 时，各容器网络独立，Gateway 容器内无法通过localhost、容器名访问 Nacos 等中间件，出现java.net.UnknownHostException: nacos未知主机异常。
根本原因
Docker 默认桥接网络隔离，不同容器默认不在同一网桥，容器内 localhost 仅代表自身容器，无法跨容器访问其他服务。
解决方案
统一声明自定义bridge全局网络app-network，所有中间件、微服务统一接入同一网络；
Compose 集群内，直接使用容器名称作为域名跨服务访问，无需硬编码 IP；
依托环境变量占位符配置，容器环境自动读取NACOS_HOST=nacos，实现跨容器域名解析，完美适配多环境。
问题 7：服务启动时序问题，Nacos 未就绪导致 Gateway 注册失败
问题描述
Compose 中depends_on仅控制容器启动顺序，不校验服务业务就绪状态；
Gateway 启动速度远快于 Nacos，频繁出现 Connection refused 连接被拒绝，持续打印 Nacos 接口请求异常。
行业踩坑点
低版本 docker-compose 3 语法不支持 service_healthy 健康依赖条件，无法直接通过配置限制服务就绪顺序，常规延时方案耦合性强、不够通用。
企业级最终解决方案（面试重点）
摒弃固定休眠延时、抛弃高版本语法兼容写法；
改造微服务Dockerfile，集成前置健康检测脚本；
容器启动前置逻辑：通过curl循环轮询检测 Nacos 服务接口，直到 Nacos 完全启动就绪后，再执行 java -jar 启动网关服务；
配合 SpringBoot Nacos 配置：关闭快速失败fail-fast=false，开启后台自动重试，提升服务容错性；
结合容器自动重启策略，实现服务自愈，彻底解决中间件未就绪引发的注册、配置拉取失败问题。
问题 8：Compose 重复部署引发容器名冲突、旧网络残留问题
问题描述
多次修改配置重新部署，残留旧容器、老旧网络、冗余镜像，导致容器命名冲突、网络缓存异常、服务连通异常。
标准化运维方案
规范容器化部署操作命令，上线前完整清理资源：
使用docker-compose down下线服务并销毁自建网络；
冲突容器通过docker rm -f 容器名强制删除；
服务更新采用docker-compose up -d --build重新构建镜像 + 动态部署，保证代码与配置同步生效。
V2.0 技术优化亮点
完成 Nacos / MySQL / Redis 集群 / Gateway 微服务 全套 Docker-Compose 一键编排部署，本地开发、容器部署环境统一；
标准化容器网络设计：统一网桥 + 容器名域名访问，完全规避 IP 硬编码，符合企业微服务容器化最佳实践；
实现依赖服务优雅启动：自研就绪检测脚本，替代传统固定延迟、高版本健康探针，兼容性更强、适配所有环境；
完善微服务容错配置：Nacos 注册中心关闭快速失败、开启重试机制，解决容器环境下服务注册雪崩问题；
沉淀完整容器化排坑链路：端口冲突、镜像构建、可执行 Jar、网络隔离、跨容器通信、服务时序、资源清理全场景问题闭环。
最终容器化架构总结
基础中间件（Nacos、MySQL、Redis）统一容器化运维，通过 Compose 统一管理生命周期；
微服务与中间件纳入同一网桥网络，基于容器名 DNS 解析实现无感知通信；
采用「环境变量 + 配置占位符」多环境适配方案，一套代码兼容本地开发、容器部署、线上服务器环境；
服务启动采用前置健康轮询 + 业务层重试双层保障，解决微服务集群启动时序经典难题；
沉淀可复用的 Dockerfile 模板、Compose 编排模板、运维操作命令，可直接复用至 SpringCloud 全项目快速容器化落地。


V2.1 环境架构迭代：Windows 开发 + WSL2 Docker 容器化 混合架构落地 & 跨环境开发踩坑
基于 V2.0 编排部署基础，完成本地开发环境与容器运行环境解耦架构升级，厘清 IDEA、Maven、JDK、WSL、Docker 之间依赖关系，定型个人长期稳定开发架构，规避跨系统环境冲突、依赖仓库隔离、远程开发绑定限制等深层次问题。
新增解决核心问题
问题 9：WSL 远程开发模式强制绑定环境，引发 JDK & Maven 全局锁定冲突
问题描述
项目若放入 WSL 子系统目录，IDEA 会强制进入 WSL 远程开发模式；
自动限制只能使用 WSL 内部 JDK、WSL Maven，无法复用本地 Windows 已有 JDK1.8、本地 Maven 仓库；
频繁弹出 JDK for importer 1.8 not found and was changed to 1.8 (WSL) 警告，导致 Maven 依赖索引异常、pom 依赖爆红、编译环境不统一。
根本原因
IDEA 对 WSL 挂载目录存在强隔离策略，WSL 文件系统项目仅允许 WSL 生态工具链；
Windows 本地 JDK、本地 Maven 仓库无法被 WSL 工程识别读取；
跨文件系统（Windows↔WSL）Maven 本地仓库路径隔离，双向无法共享依赖包，造成重复下载、环境错乱。
解决方案
放弃 WSL 远程开发模式，项目代码统一存放 Windows 本地磁盘；
IDEA 全程使用本地 Windows 环境：
本地 JDK1.8 做代码提示、语法校验、Maven 索引导入；
复用自有 Maven 3.6.3 与本地私有仓库，依赖无需重复下载；
剥离 WSL 多余依赖，WSL 仅作为 Docker 承载环境，不安装 JDK、不安装 Maven，彻底规避双环境冲突。
问题 10：开发与运行环境认知误区：容器 JDK 与本地 IDE JDK 职责混淆
问题描述
初期误解：认为 WSL/Linux 必须安装 JDK 才能运行微服务；
实际出现冗余安装 JDK17、多版本混乱、环境变量冲突等无效配置。
核心原理梳理（面试可直接口述）
代码编辑 & Maven 编译索引：依赖 IDEA 绑定的本地 Windows JDK，仅用于语法检查、类库解析、代码补全；
项目真实运行环境：完全由 Docker 容器内部提供，每个微服务镜像内置独立 JDK，与宿主机、WSL 环境无任何绑定；
WSL2 只负责提供纯净 Linux 内核与 Docker 运行时，无需预装任何开发编译环境，实现开发与运行完全解耦。
问题 11：跨系统文件共享、Jar 打包与容器挂载架构标准化
核心概念落地
本地打包流程标准化：
Windows 端 IDEA 借助 Maven package 一键编译打包，生成 target/*.jar；
Docker 构建直接读取 Windows 本地 target 产物，无需上传、无需拷贝至 WSL 系统目录；
容器挂载机制理解与应用：
WSL 自动挂载 Windows 磁盘，以 /mnt/盘符 目录实现双向文件互通；
Docker 可通过目录挂载实现宿主机文件共享，等同于给 Linux 系统外接 Windows 文件夹，实现配置、日志、Jar 包实时同步；
构建流程简化：
本地修改代码 → IDEA 重新打包 → Compose 重新构建镜像 / 重启容器，一套流程高效迭代。
V2.1 最终定型企业级混合架构
编码层：Windows + IDEA + 本地 JDK1.8 + 本地私有 Maven 仓库，开发体验稳定、生态成熟；
运行层：WSL2 + Docker Desktop + Docker Compose，容器环境纯正 Linux 标准，与生产服务器环境一致；
中间件层：Nacos / MySQL / Redis 全部容器化部署，统一自定义网桥网络，容器名 DNS 互通；
适配层：统一采用「环境变量 + 配置占位符」，剥离固定 IP，一套配置兼容：本地 IDE 启动、Docker 容器、线上 Linux 服务器；
轻量化设计：WSL 只保留 Docker 能力，不冗余安装 Java、Maven 等编译工具，环境极简、故障面大幅减少。
架构优势总结
规避双系统 JDK、Maven 仓库隔离问题，减少重复依赖下载与环境报错；
告别 WSL 远程开发强绑定限制，自由切换本地工具链，降低入门与维护成本；
代码在 Windows、运行在标准 Linux 容器，兼顾开发效率与生产环境一致性；
完整厘清「编译 JDK、运行 JDK、容器环境、宿主机环境」边界认知，夯实微服务容器化底层理解；
沉淀轻量化 WSL+Docker 最佳实践，适合个人开发、面试项目、后端入门落地复用。

创建了github仓库并上传。