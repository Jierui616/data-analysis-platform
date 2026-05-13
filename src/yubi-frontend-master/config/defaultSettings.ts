import { ProLayoutProps } from '@ant-design/pro-components';

/**
 * @name
 */
const Settings: ProLayoutProps & {
  pwa?: boolean;
  logo?: string;
  /** ProLayout：避免 md 断点默认收起侧栏（演示时保持展开） */
  defaultCollapsed?: boolean;
  breakpoint?: false;
} = {
  navTheme: 'light',
  /** 与大屏主题主色对齐，便于全站观感统一 */
  colorPrimary: '#2f84ff',
  layout: 'mix',
  contentWidth: 'Fluid',
  fixedHeader: false,
  fixSiderbar: true,
  /** @see @ant-design/pro-layout：md 宽度下默认 true 会收起菜单，答辩/大屏路由希望默认展开 */
  defaultCollapsed: false,
  /** 禁用侧栏响应式折叠断点，避免窗口变窄时菜单被收起 */
  breakpoint: false,
  colorWeak: false,
  title: '湖仓数智金融分析平台',
  pwa: true,
  logo: '/logo.svg',
  iconfontUrl: '',
  token: {
    // 参见ts声明，demo 见文档，通过token 修改样式
    //https://procomponents.ant.design/components/layout#%E9%80%9A%E8%BF%87-token-%E4%BF%AE%E6%94%B9%E6%A0%B7%E5%BC%8F
  },
};

export default Settings;
