package alia;

import java.awt.*;
import javax.swing.*;
import javax.swing.plaf.basic.*;
import javax.swing.text.JTextComponent;

/** Scoped charcoal styling: never changes global Swing defaults or warning colours. */
final class PilotTheme {
    static final Color BASE=new Color(0x121212),SURFACE=new Color(0x18181B),TEXT=new Color(0xE0E0E0),MUTED=new Color(0xA3A3A3),LINE=new Color(0x343438),SELECTED=new Color(0x2B2B30);
    static void button(JButton b){b.setUI(new BasicButtonUI());b.setFocusPainted(false);b.setOpaque(true);}
    static void apply(Component component){
        if(component instanceof JTextComponent t){t.setBackground(SURFACE);t.setForeground(TEXT);t.setCaretColor(TEXT);t.setSelectionColor(SELECTED);t.setSelectedTextColor(TEXT);if(t instanceof JTextField)t.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE),BorderFactory.createEmptyBorder(2,4,2,4)));}
        if(component instanceof JButton b){button(b);if(b.getParent() instanceof JSpinner){b.setBackground(SURFACE);b.setForeground(TEXT);}}
        if(component instanceof JSpinner spinner){spinner.setBackground(SURFACE);spinner.setForeground(TEXT);spinner.setBorder(BorderFactory.createLineBorder(LINE));}
        if(component instanceof JComboBox<?> combo){
            combo.setBackground(SURFACE);combo.setForeground(TEXT);
            combo.setUI(new BasicComboBoxUI(){
                private final JLabel selectedValue=new JLabel();
                protected JButton createArrowButton(){return arrow(SwingConstants.SOUTH);}
                public void paintCurrentValueBackground(Graphics g,Rectangle r,boolean focus){g.setColor(SURFACE);g.fillRect(r.x,r.y,r.width,r.height);}
                public void paintCurrentValue(Graphics g,Rectangle r,boolean focus){selectedValue.setText(comboBox.getSelectedItem()==null?"":comboBox.getSelectedItem().toString());selectedValue.setFont(comboBox.getFont());selectedValue.setOpaque(true);selectedValue.setBackground(SURFACE);selectedValue.setForeground(comboBox.isEnabled()?TEXT:MUTED);selectedValue.setBorder(BorderFactory.createEmptyBorder(0,5,0,2));currentValuePane.paintComponent(g,selectedValue,comboBox,r.x,r.y,r.width,r.height,true);}
            });combo.setBackground(SURFACE);combo.setForeground(TEXT);combo.setBorder(BorderFactory.createLineBorder(LINE));
            combo.setRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> list,Object value,int i,boolean selected,boolean focus){Component c=super.getListCellRendererComponent(list,value,i,selected,focus);c.setBackground(selected?SELECTED:SURFACE);c.setForeground(TEXT);return c;}});
        }
        if(component instanceof JCheckBox check){check.setOpaque(false);check.setForeground(TEXT);check.setIcon(new Icon(){public int getIconWidth(){return 18;}public int getIconHeight(){return 18;}public void paintIcon(Component c,Graphics g,int x,int y){g.setColor(SURFACE);g.fillRect(x,y,17,17);g.setColor(LINE);g.drawRect(x,y,17,17);if(((AbstractButton)c).isSelected()){Graphics2D gg=(Graphics2D)g.create();gg.setColor(PilotDisplay.CYAN);gg.setStroke(new BasicStroke(2.5f));gg.drawLine(x+4,y+9,x+8,y+13);gg.drawLine(x+8,y+13,x+14,y+4);gg.dispose();}}});}
        if(component instanceof JScrollPane scroll){scroll.setBackground(BASE);scroll.getViewport().setBackground(BASE);scroll.setBorder(BorderFactory.createLineBorder(LINE));}
        if(component instanceof JScrollBar bar){bar.setUI(new BasicScrollBarUI(){protected void configureScrollBarColors(){thumbColor=LINE;trackColor=BASE;thumbDarkShadowColor=LINE;thumbHighlightColor=SELECTED;thumbLightShadowColor=LINE;}protected JButton createDecreaseButton(int direction){return arrow(direction);}protected JButton createIncreaseButton(int direction){return arrow(direction);}});}
        if(component instanceof JTabbedPane tabs){tabs.setBackground(SURFACE);tabs.setForeground(TEXT);tabs.setUI(new BasicTabbedPaneUI(){
            protected Insets getTabInsets(int placement,int index){return new Insets(10,12,10,12);}
            protected void paintTabBackground(Graphics g,int placement,int index,int x,int y,int w,int h,boolean selected){g.setColor(selected?SELECTED:SURFACE);g.fillRect(x,y,w,h);}
            protected void paintTabBorder(Graphics g,int placement,int index,int x,int y,int w,int h,boolean selected){g.setColor(selected?PilotDisplay.CYAN:LINE);g.drawLine(x,y+h-1,x+w,y+h-1);}
            protected void paintContentBorder(Graphics g,int placement,int selected){g.setColor(LINE);g.drawRect(0,calculateTabAreaHeight(placement,runCount,maxTabHeight),tabs.getWidth()-1,tabs.getHeight()-calculateTabAreaHeight(placement,runCount,maxTabHeight)-1);}
            protected void paintFocusIndicator(Graphics g,int p,Rectangle[] r,int i,Rectangle icon,Rectangle text,boolean selected){}
            protected JButton createScrollButton(int direction){return arrow(direction);}
        });}
        if(component instanceof JList<?> list){list.setBackground(SURFACE);list.setForeground(TEXT);list.setSelectionBackground(SELECTED);list.setSelectionForeground(TEXT);}
        if(component instanceof Container parent)for(Component child:parent.getComponents())apply(child);
    }
    private static JButton arrow(int direction){JButton b=new BasicArrowButton(direction,SURFACE,LINE,TEXT,LINE);b.setOpaque(true);return b;}
}
