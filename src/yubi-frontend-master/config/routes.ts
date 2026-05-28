export default [
  { path: '/user', layout: false, routes: [{ path: '/user/login', component: './User/Login' }] },
  { path: '/', redirect: '/ai/realtime' },

  /** 兼容旧链接（菜单已分组到新路径） */
  { path: '/add_chart', redirect: '/ai/realtime', hideInMenu: true },
  { path: '/add_chart_async', redirect: '/ai/async', hideInMenu: true },
  { path: '/deep_chat', redirect: '/ai/chat', hideInMenu: true },
  { path: '/my_chart', redirect: '/biz/charts', hideInMenu: true },
  { path: '/lineage_center', redirect: '/biz/lineage', hideInMenu: true },
  { path: '/data_import', redirect: '/biz/import', hideInMenu: true },
  { path: '/bi_bigscreen', redirect: '/biz/bigscreen', hideInMenu: true },

  {
    path: '/ai',
    name: '智能分析',
    icon: 'barChart',
    routes: [
      { path: '/ai', redirect: '/ai/realtime' },
      { path: '/ai/realtime', name: '实时生成', component: './AddChart' },
      { path: '/ai/async', name: '异步生成', component: './AddChartAsync' },
      { path: '/ai/chat', name: '深度对话', icon: 'message', component: './DeepChat' },
      { path: '/ai/auto-eda', name: '自主探索', icon: 'compass', component: './AutoEDA' },
    ],
  },

  { path: '/chart_result/:id', hideInMenu: true, component: './ChartResult' },
  { path: '/insight-report/:sessionId', name: '分析报告', icon: 'fileText', component: './InsightReport', hideInMenu: true },

  {
    path: '/biz',
    name: '数据与经营',
    icon: 'appstore',
    routes: [
      { path: '/biz', redirect: '/biz/charts' },
      { path: '/biz/charts', name: '图表管理与回看', icon: 'pieChart', component: './MyChart' },
      { path: '/biz/lineage', name: '血缘追踪中心', icon: 'apartment', component: './LineageCenter' },
      { path: '/biz/import', name: '成本与排班导入', icon: 'upload', component: './DataImportCenter' },
      { path: '/biz/bigscreen', name: '经营指挥大屏', icon: 'dashboard', component: './BIOperationScreen' },
    ],
  },

  {
    path: '/risk_portrait',
    name: '风险与信用画像',
    icon: 'idcard',
    routes: [
      { path: '/risk_portrait', redirect: '/risk_portrait/overview' },
      { path: '/risk_portrait/overview', name: '画像总览', component: './RiskPortrait/Overview' },
      { path: '/risk_portrait/store', name: '门店/加盟商画像', component: './RiskPortrait/Store' },
      { path: '/risk_portrait/member', name: '会员画像', component: './RiskPortrait/Member' },
      { path: '/risk_portrait/anonymous', name: '匿名用户画像', component: './RiskPortrait/Anonymous' },
      { path: '/risk_portrait/rules', name: '策略与规则', component: './RiskPortrait/Rules' },
      { path: '/risk_portrait/audit', name: '命中与留痕', component: './RiskPortrait/Audit' },
    ],
  },

  {
    path: '/admin',
    name: '系统管理',
    icon: 'crown',
    access: 'canAdmin',
    routes: [
      { path: '/admin', redirect: '/admin/sub-page' },
      { path: '/admin/sub-page', name: '管理控制台', component: './Admin' },
    ],
  },
  { path: '*', layout: false, component: './404' },
];
