# 项目名称：iagent

## 简介
基于 agentscope构建 的 Agentic 服务，用于为用户提供投资分析服务。

## 目录结构
- `src/` - 主应用代码
- `src/main/` - 主应用代码
- `src/test/` - 测试代码
- `workspace/` - 工作空间

## 架构原则
- 单一职责
- 迪米特法则
- DRY原则：如果某段代码重复出现，则应该抽离为方法。
- 决策原则：优先完成需求，然后要求稳定执行，其次要求方案的简洁和易于维护，最后考量性能和开销。

## 代码实现偏好
### 简洁
- lombok注解优于get/set方法
- 基础工具在目录io/invest/iagent/utils，时间、文件、http请求相关的方法应抽取到这里，以防止重复。

### 调试
- 临时文件请存放在workspace/temp下

## 权限控制规则
### 文件操作
#### 可编辑的目录（无须询问）
- D:\dev\codes\github\iagent

#### 可访问的目录（无须询问）
- D:\dev\codes\

### 工具操作
#### 允许执行的操作（无须询问）
- mvn * 
- python *
- git *