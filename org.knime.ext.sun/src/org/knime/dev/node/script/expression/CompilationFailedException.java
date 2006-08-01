/*  
 * -------------------------------------------------------------------
 * This source code, its documentation and all appendant files
 * are protected by copyright law. All rights reserved.
 *
 * Copyright, 2003 - 2006
 * University of Konstanz, Germany.
 * Chair for Bioinformatics and Information Mining
 * Prof. Dr. Michael R. Berthold
 *
 * You may not modify, publish, transmit, transfer or sell, reproduce,
 * create derivative works from, distribute, perform, display, or in
 * any way exploit any of the content, in whole or in part, except as
 * otherwise expressly permitted in writing by the copyright owner or
 * as specified in the license file distributed with this product.
 *
 * If you have any quesions please contact the copyright holder:
 * website: www.knime.org
 * email: contact@knime.org
 * -------------------------------------------------------------------
 * 
 */
package org.knime.dev.node.script.expression;

/**
 * An exception being thrown when the compilation fails.
 * 
 * @author Bernd Wiswedel, University of Konstanz
 */
public class CompilationFailedException extends Exception {
    /**
     * @param msg detailed (more or less) error message
     * @see Exception#Exception(java.lang.String)
     */
    public CompilationFailedException(final String msg) {
        super(msg);
    }

    /**
     * @param cause the cause for this exception
     * @see Exception#Exception(java.lang.Throwable)
     */
    public CompilationFailedException(final Throwable cause) {
        super(cause);
    }
}
