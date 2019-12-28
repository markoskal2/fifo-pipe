/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package chatClient;

import java.io.File;
import javax.swing.filechooser.FileFilter;

/**
 *
 * @author Lefos
 */
public class OpenFileFilter extends FileFilter {

    String description = "";
    String[] fileExt ;

    public OpenFileFilter(String extension) {
        fileExt = extension.split(",");
    }

    public OpenFileFilter(String extension, String typeDescription) {
        extension= extension.replaceAll(" ", "");
        fileExt = extension.split(",");
        this.description = typeDescription;
    }

    @Override
    public boolean accept(File f) {
        if (f.isDirectory()) {
            return true;
        }
        for(int i=0;i<fileExt.length;i++){
            if(f.getName().toLowerCase().endsWith(fileExt[i])){
                return(true);
            }
        }
        return false;
    }

    @Override
    public String getDescription() {
        return description;
    }
}