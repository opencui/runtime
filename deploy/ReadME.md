# 功能 
#### 使用导出的Agent压缩文件来快速私有化部署

# 依赖的
1. Python3
2. Ansible
3. Docker
4. Docker-compose

# 操作顺序
1. 根据配置修改hosts.ini文件，本例子使用单机模式
   1. BasePath是定义redis服务的基础路径
2. 把chatbot文件解压到项目的agents目录中，记录OrgName和AgentName
3. 进入到deploy目录
4. 运行ansible-playbook opencui-build.yaml -e OrgName=me.demo -e AgentName=tableReservationApp生成
   1. 日志目录<repo>/build/log
   2. RuntimeThin目录<repo>/deploy/m2/
5. 运行ansible-playbook opencui.yaml -e OrgName=me.demo -e AgentName=tableReservationApp -e RedisCluster=redis -e DuCluster=du -e DucklingCluster=duckling -e DispatcherCluster=dispatcher -e RepoPath=$(pwd)/../
6. 更新chatbot，重复2,3,4,5